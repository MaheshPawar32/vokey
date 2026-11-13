package com.dev.vokey

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Synthetic 16 kHz test signals that model real sounds (no recordings). */
internal object SignalLab {
    const val SR = 16_000

    fun silence(ms: Int) = DoubleArray(SR * ms / 1000)

    /** Low room noise (~-56 dBFS). */
    fun roomNoise(ms: Int, amplitude: Double = 50.0, seed: Int = 1): DoubleArray {
        val r = Random(seed)
        return DoubleArray(SR * ms / 1000) { (r.nextDouble() * 2 - 1) * amplitude }
    }

    /**
     * Hand clap: broadband burst with ~1 ms attack, ~6 ms decay, plus a
     * weaker reverberant room tail (~80 ms decay).
     */
    fun clap(amplitude: Double = 20_000.0, seed: Int = 7): DoubleArray {
        val r = Random(seed)
        val n = SR * 250 / 1000
        return DoubleArray(n) { i ->
            val t = i * 1000.0 / SR
            val attack = min(1.0, t / 0.7)
            val direct = exp(-t / 6.0) * attack
            val tail = 0.10 * exp(-t / 80.0) * attack
            (r.nextDouble() * 2 - 1) * amplitude * (direct + tail)
        }
    }

    /** Voiced vowel: f0 with 1/k harmonics up to 4 kHz, smooth envelope. */
    fun vowel(ms: Int, f0: Double = 140.0, amplitude: Double = 6_000.0, attackMs: Double = 20.0): DoubleArray {
        val n = SR * ms / 1000
        val harmonics = (4_000 / f0).toInt()
        return DoubleArray(n) { i ->
            val t = i * 1000.0 / n * ms / ms
            val tMs = i * 1000.0 / SR
            val env = min(1.0, tMs / attackMs) * min(1.0, (ms - tMs) / 30.0).coerceAtLeast(0.0)
            var v = 0.0
            for (k in 1..harmonics) v += sin(2 * PI * f0 * k * i / SR) / k
            v * amplitude * 0.5 * env + 0 * t
        }
    }

    /** Plosive consonant burst ("t"/"k"): ~8 ms broadband click. */
    fun plosive(amplitude: Double = 7_000.0, seed: Int = 3): DoubleArray {
        val r = Random(seed)
        val n = SR * 8 / 1000
        return DoubleArray(n) { i -> (r.nextDouble() * 2 - 1) * amplitude * exp(-i * 1000.0 / SR / 3.0) }
    }

    /** Continuous speech: plosive + short gap + vowel syllables, small pauses. */
    fun speech(seconds: Int, seed: Int = 11): DoubleArray {
        val r = Random(seed)
        val out = ArrayList<Double>()
        out.addAll(roomNoise(300).toList())
        while (out.size < SR * seconds) {
            if (r.nextBoolean()) out.addAll(plosive(5_000.0 + r.nextDouble() * 5_000, r.nextInt()).toList())
            out.addAll(silence(10 + r.nextInt(20)).toList())
            out.addAll(vowel(120 + r.nextInt(150), 110.0 + r.nextDouble() * 120, 4_000.0 + r.nextDouble() * 4_000).toList())
            out.addAll(roomNoise(30 + r.nextInt(80), seed = r.nextInt()).toList())
        }
        return out.toDoubleArray()
    }

    /** Music kick drum: ~60 Hz thump, fast attack, 80 ms decay. */
    fun kick(amplitude: Double = 20_000.0): DoubleArray {
        val n = SR * 250 / 1000
        return DoubleArray(n) { i ->
            val t = i * 1000.0 / SR
            sin(2 * PI * 60 * i / SR) * amplitude * exp(-t / 80.0) * min(1.0, t / 1.0)
        }
    }

    fun tone(ms: Int, hz: Double, amplitude: Double = 8_000.0) =
        DoubleArray(SR * ms / 1000) { i -> sin(2 * PI * hz * i / SR) * amplitude }

    fun mix(vararg parts: DoubleArray): DoubleArray {
        val n = parts.maxOf { it.size }
        return DoubleArray(n) { i -> parts.sumOf { if (i < it.size) it[i] else 0.0 } }
    }

    fun concat(vararg parts: DoubleArray): DoubleArray =
        parts.flatMap { it.toList() }.toDoubleArray()

    /** Feeds a signal in 20 ms frames; returns the times (ms) of detections. */
    fun runClap(detector: ClapDetector, signal: DoubleArray): List<Long> {
        val hits = mutableListOf<Long>()
        val frame = ShortArray(320)
        var i = 0
        var t = 0L
        while (i + 320 <= signal.size) {
            for (k in 0 until 320) {
                frame[k] = signal[i + k].coerceIn(-32_767.0, 32_767.0).toInt().toShort()
            }
            t += 20
            if (detector.process(frame, 320, t).clapDetected) hits.add(t)
            i += 320
        }
        return hits
    }

    fun runWhistle(detector: WhistleDetector, signal: DoubleArray): Int {
        var hits = 0
        val frame = ShortArray(320)
        var i = 0
        var t = 0L
        while (i + 320 <= signal.size) {
            for (k in 0 until 320) {
                frame[k] = signal[i + k].coerceIn(-32_767.0, 32_767.0).toInt().toShort()
            }
            t += 20
            if (detector.process(frame, 320, t).detected) hits++
            i += 320
        }
        return hits
    }
}
