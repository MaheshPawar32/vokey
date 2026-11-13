package com.dev.vokey

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Hand-clap detector (pure Kotlin, JVM-testable; no audio leaves this class).
 *
 * v2 — a clap is accepted only when ALL of these hold, measured on 2 ms
 * blocks at 16 kHz:
 *  1. Loud: peak block is [minLevelDb] above the adaptive noise floor and
 *     above an absolute level.
 *  2. Sudden attack: energy rose by [minAttackRatio] within ~4–6 ms
 *     (speech syllables build up over 10–30 ms).
 *  3. Quiet before: the ~80 ms before the onset was near the noise floor
 *     (continuous talking/music is never "quiet before").
 *  4. Bright: broadband energy (high first-difference ratio). Voices are
 *     mostly below 1 kHz; kicks/thumps are lower still.
 *  5. Short: 50–90 ms after onset the energy has dropped by [maxTailDb]
 *     (a spoken "t"/"k" burst is followed by a sustained vowel).
 *  6. Impulsive: the sound stays within 10 dB of its peak for at most
 *     [maxImpulseBlocks] blocks (clap ≈ 5–15 ms; consonant ≈ 20–40 ms).
 *  7. No voice nearby: no loud *voiced* (dull) sound within [voiceGuardMs]
 *     before the onset or within 250 ms after it. Consonants always sit
 *     next to vowels; a clap does not. (Found on a real phone: bright
 *     consonants followed by a short pause passed checks 1–5.)
 * The decision is made ~250 ms after the onset.
 *
 * [onDiagnostic] receives numeric feature summaries for tuning (never audio).
 */
internal class ClapDetector(
    private val minLevelDb: Double = 17.0,
    private val minAbsRms: Double = 700.0,
    private val minAttackRatio: Double = 8.0,
    private val maxPreFactor: Double = 4.0,
    private val minHighFreqRatio: Double = 1.0,
    private val maxTailDb: Double = -8.0,
    private val refractoryMs: Long = 450L,
    private val maxImpulseBlocks: Int = 12,
    private val voiceGuardMs: Long = 300L,
    private val onDiagnostic: ((String) -> Unit)? = null,
) {
    companion object {
        const val BLOCK = 32 // 2 ms at 16 kHz
        private const val BLOCK_MS = 2L
        private const val RING = 64 // 128 ms of block energies
        private const val WARMUP_BLOCKS = 100 // 200 ms to learn the floor
        private const val PEAK_BLOCKS = 4 // peak + brightness window (8 ms)
        private const val TAIL_START = 25 // 50 ms after onset
        private const val TAIL_END = 45 // 90 ms after onset
        private const val IMPULSE_WINDOW = 40 // 80 ms checked for impulse length
        private const val VOICE_AFTER_START = 10 // ignore the clap's own 20 ms
        private const val DECISION = 125 // 250 ms after onset
        private const val VOICED_MIN_DB = 10.0 // voiced block: >= floor + 10 dB …
        private const val VOICED_MAX_BRIGHT = 0.3 // … and dull (voice/vowel)
        private const val MIN_FLOOR_E = 400.0 // RMS 20: guards digital silence

        fun forSensitivity(level: String): ClapDetector =
            forPercent(Sensitivity.fromPreset(level))

        /** 0 = strict, 50 = balanced, 100 = very sensitive. */
        fun forPercent(
            percent: Int,
            refractoryMs: Long = 450L,
            onDiagnostic: ((String) -> Unit)? = null,
        ): ClapDetector {
            fun lerp(low: Double, normal: Double, high: Double) =
                Sensitivity.interpolate(percent, low, normal, high)
            return ClapDetector(
                minLevelDb = lerp(24.0, 17.0, 11.0),
                minAbsRms = lerp(1_200.0, 700.0, 350.0),
                minAttackRatio = lerp(12.0, 8.0, 5.0),
                maxPreFactor = lerp(3.0, 4.0, 6.0),
                // Measured on a realme RMX1931 (2026-11-13, 8.5 min, 37k sounds):
                // voice/thumps 0.01–0.06, accepted claps 2.1–3.4. 1.0 keeps a
                // wide margin on both sides (mics with weaker highs still pass).
                minHighFreqRatio = lerp(1.4, 1.0, 0.7),
                maxTailDb = lerp(-10.0, -8.0, -6.0),
                maxImpulseBlocks = lerp(9.0, 12.0, 16.0).toInt(),
                refractoryMs = refractoryMs,
                onDiagnostic = onDiagnostic,
            )
        }
    }

    data class FrameResult(
        val rms: Double,
        val peak: Int,
        val noiseFloorRms: Double,
        val clapDetected: Boolean,
        val clapCount: Int,
    )

    private val ring = DoubleArray(RING)
    private var ringPos = 0
    private var blocksSeen = 0L
    private var floorE = MIN_FLOOR_E
    private var prevSample = 0
    private var lastClapAtMs = Long.MIN_VALUE
    private var clapCount = 0

    // Candidate state.
    private var inCandidate = false
    private var candStartMs = 0L
    private var candBlocks = 0
    private var candPeakE = 0.0
    private var candSumE = 0.0
    private var candSumD = 0.0
    private var candTailSum = 0.0
    private var candTailN = 0
    private var candAttack = 0.0
    private var candPre = 0.0
    private var candLevelDb = 0.0
    private var candImpulseBlocks = 0
    private var candVoiceAfter = false
    private var lastVoicedMs = Long.MIN_VALUE

    private fun ringAgo(n: Int): Double = ring[((ringPos - n) % RING + RING) % RING]

    private fun push(e: Double) {
        ring[ringPos] = e
        ringPos = (ringPos + 1) % RING
    }

    fun process(samples: ShortArray, length: Int, nowMs: Long): FrameResult {
        if (length <= 0 || length > samples.size) {
            return FrameResult(0.0, 0, sqrt(floorE), false, clapCount)
        }
        var sumSq = 0.0
        var peak = 0
        for (i in 0 until length) {
            val v = samples[i].toInt()
            sumSq += v.toDouble() * v
            val a = abs(v)
            if (a > peak) peak = a
        }
        val blocks = length / BLOCK
        var detected = false
        for (b in 0 until blocks) {
            var e = 0.0
            var d = 0.0
            val base = b * BLOCK
            for (i in 0 until BLOCK) {
                val v = samples[base + i].toInt()
                val diff = v - prevSample
                e += v.toDouble() * v
                d += diff.toDouble() * diff
                prevSample = v
            }
            e /= BLOCK
            d /= BLOCK
            val blockMs = nowMs - (blocks - 1 - b) * BLOCK_MS
            if (handleBlock(e, d, blockMs)) detected = true
        }
        return FrameResult(sqrt(sumSq / length), peak, sqrt(floorE), detected, clapCount)
    }

    /** Returns true when a clap is confirmed on this block. */
    private fun handleBlock(e: Double, d: Double, blockMs: Long): Boolean {
        blocksSeen++
        val voiced = e >= floorE * 10.0 /* +10 dB */ && d / max(e, 1.0) < VOICED_MAX_BRIGHT
        if (voiced) lastVoicedMs = blockMs
        if (blocksSeen <= WARMUP_BLOCKS) {
            floorE = max(MIN_FLOOR_E, floorE + (e - floorE) / blocksSeen)
            push(e)
            return false
        }
        if (inCandidate) {
            val confirmed = continueCandidate(e, d, voiced)
            push(e)
            return confirmed
        }
        val levelDb = 10 * log10(max(e, 1.0) / floorE)
        val outsideRefractory = lastClapAtMs == Long.MIN_VALUE || blockMs - lastClapAtMs >= refractoryMs
        if (levelDb >= minLevelDb && e >= minAbsRms * minAbsRms && outsideRefractory) {
            val before = max(max(ringAgo(2), ringAgo(3)), floorE)
            val attack = e / before
            var preSum = 0.0
            for (n in 5..45) preSum += ringAgo(n)
            val pre = (preSum / 41) / floorE
            val voiceBefore = lastVoicedMs != Long.MIN_VALUE && blockMs - lastVoicedMs < voiceGuardMs
            if (attack >= minAttackRatio && pre <= maxPreFactor && !voiceBefore) {
                inCandidate = true
                candStartMs = blockMs
                candBlocks = 0
                candPeakE = e
                candSumE = e
                candSumD = d
                candTailSum = 0.0
                candTailN = 0
                candAttack = attack
                candPre = pre
                candLevelDb = levelDb
                candImpulseBlocks = 1
                candVoiceAfter = false
            } else {
                diag("reject level=%.1fdB attack=%.1f pre=%.1fx (%s)".format(
                    levelDb, attack, pre,
                    when {
                        attack < minAttackRatio -> "slow attack"
                        voiceBefore -> "voice just before"
                        else -> "not quiet before"
                    },
                ))
            }
            push(e)
            return false
        }
        // Background: fall quickly, rise slowly (~1 s), so loud events
        // don't drag the floor up but a noisier room is learned.
        floorE = if (e < floorE) floorE * 0.95 + e * 0.05 else floorE * 0.999 + e * 0.001
        if (floorE < MIN_FLOOR_E) floorE = MIN_FLOOR_E
        push(e)
        return false
    }

    private fun continueCandidate(e: Double, d: Double, voiced: Boolean): Boolean {
        candBlocks++
        if (candBlocks < IMPULSE_WINDOW && e >= candPeakE * 0.1) candImpulseBlocks++
        if (candBlocks >= VOICE_AFTER_START && voiced) candVoiceAfter = true
        if (candBlocks < PEAK_BLOCKS) {
            if (e > candPeakE) candPeakE = e
            candSumE += e
            candSumD += d
        }
        if (candBlocks in TAIL_START until TAIL_END) {
            candTailSum += e
            candTailN++
        }
        if (candBlocks < DECISION) return false

        inCandidate = false
        val hf = candSumD / max(candSumE, 1.0)
        val tailDb = 10 * log10(max(candTailSum / max(candTailN, 1), 1.0) / candPeakE)
        val ok = hf >= minHighFreqRatio && tailDb <= maxTailDb &&
            candImpulseBlocks <= maxImpulseBlocks && !candVoiceAfter
        diag("%s level=%.1fdB attack=%.1f pre=%.1fx bright=%.2f tail=%.1fdB impulse=%dms%s".format(
            if (ok) "CLAP" else "reject",
            candLevelDb, candAttack, candPre, hf, tailDb, candImpulseBlocks * 2,
            when {
                ok -> ""
                hf < minHighFreqRatio -> " (too dull: voice/thump)"
                candVoiceAfter -> " (voice right after: speech consonant)"
                candImpulseBlocks > maxImpulseBlocks -> " (not impulsive: consonant/noise burst)"
                else -> " (too long: speech/sustained)"
            },
        ))
        if (ok) {
            lastClapAtMs = candStartMs
            clapCount++
        }
        return ok
    }

    private fun diag(message: String) {
        onDiagnostic?.invoke(message)
    }
}
