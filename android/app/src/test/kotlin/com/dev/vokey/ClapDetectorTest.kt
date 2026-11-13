package com.dev.vokey

import com.dev.vokey.SignalLab.clap
import com.dev.vokey.SignalLab.concat
import com.dev.vokey.SignalLab.kick
import com.dev.vokey.SignalLab.mix
import com.dev.vokey.SignalLab.roomNoise
import com.dev.vokey.SignalLab.runClap
import com.dev.vokey.SignalLab.speech
import com.dev.vokey.SignalLab.tone
import com.dev.vokey.SignalLab.vowel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClapDetectorTest {
    private fun withRoom(signal: DoubleArray) = mix(signal, roomNoise(signal.size * 1000 / SignalLab.SR + 20))

    // --- Must detect --------------------------------------------------------

    @Test
    fun clapAfterQuietIsDetectedOnce() {
        val hits = runClap(ClapDetector(), withRoom(concat(roomNoise(800), clap(), roomNoise(600))))
        assertEquals(1, hits.size)
        // Decision ~250 ms after onset (onset at 800 ms).
        assertTrue(hits.single() in 1_000..1_150)
    }

    @Test
    fun doubleClapGivesTwoDetections() {
        val det = ClapDetector(refractoryMs = 220)
        val gap = roomNoise(100)
        val hits = runClap(det, withRoom(concat(roomNoise(800), clap(), gap, clap(seed = 9), roomNoise(600))))
        assertEquals(2, hits.size)
    }

    @Test
    fun clapInModeratelyNoisyRoomIsDetected() {
        val noisy = roomNoise(1_600, amplitude = 400.0, seed = 5) // ~-38 dBFS (fan/AC)
        val signal = mix(noisy, concat(SignalLab.silence(900), clap()))
        assertEquals(1, runClap(ClapDetector(), signal).size)
    }

    // --- Must reject ------------------------------------------------------

    @Test
    fun continuousSpeechNeverTriggers() {
        for (seed in 1..5) {
            val hits = runClap(ClapDetector(), withRoom(speech(seconds = 8, seed = seed)))
            assertEquals("speech seed $seed", 0, hits.size)
        }
    }

    @Test
    fun loudSpeechAtHighSensitivityStillRejected() {
        val hits = runClap(ClapDetector.forPercent(90), withRoom(speech(seconds = 8, seed = 42)))
        assertEquals(0, hits.size)
    }

    @Test
    fun plosiveFollowedByVowelIsNotAClap() {
        // Isolated word after silence: "t" burst then vowel ("ta").
        val word = concat(SignalLab.plosive(9_000.0), SignalLab.silence(15), vowel(220))
        assertEquals(0, runClap(ClapDetector(), withRoom(concat(roomNoise(800), word, roomNoise(500)))).size)
    }

    @Test
    fun consonantThenPauseThenVowelIsNotAClap() {
        // Real-phone failure: bright "t"/"ch" burst, ~100 ms pause, then a
        // vowel (TTS and fast talkers). Passes brightness and tail checks.
        for (pauseMs in listOf(60, 100, 150, 200)) {
            val word = concat(SignalLab.plosive(12_000.0), roomNoise(pauseMs), vowel(220))
            val hits = runClap(ClapDetector.forPercent(100), withRoom(concat(roomNoise(800), word, roomNoise(500))))
            assertEquals("pause $pauseMs ms", 0, hits.size)
        }
    }

    @Test
    fun consonantRightAfterTalkingIsNotAClap() {
        // Vowel ends, 120 ms pause, then a bright burst with nothing after.
        val signal = concat(roomNoise(800), vowel(300), roomNoise(120), SignalLab.plosive(12_000.0), roomNoise(600))
        assertEquals(0, runClap(ClapDetector.forPercent(100), withRoom(signal)).size)
    }

    @Test
    fun longNoiseBurstIsNotImpulsive() {
        // 35 ms "ch"/"sh" burst that then stops (no tail): too long for a clap.
        val r = kotlin.random.Random(2)
        val burst = DoubleArray(SignalLab.SR * 35 / 1000) { (r.nextDouble() * 2 - 1) * 9_000 }
        val signal = concat(roomNoise(800), burst, roomNoise(500))
        assertEquals(0, runClap(ClapDetector.forPercent(100), withRoom(signal)).size)
    }

    @Test
    fun heldVowelIsNotAClap() {
        val hits = runClap(ClapDetector(), withRoom(concat(roomNoise(800), vowel(600, attackMs = 5.0), roomNoise(400))))
        assertEquals(0, hits.size)
    }

    @Test
    fun kickDrumThumpIsNotAClap() {
        assertEquals(0, runClap(ClapDetector(), withRoom(concat(roomNoise(800), kick(), roomNoise(400)))).size)
    }

    @Test
    fun steadyToneIsNotAClap() {
        assertEquals(0, runClap(ClapDetector(), withRoom(concat(roomNoise(800), tone(800, 1_000.0), roomNoise(300)))).size)
    }

    @Test
    fun roomNoiseRisingLikeAutoGainIsNotAClap() {
        // Noise level ramps 50 -> 1500 over 300 ms (AGC pumping / fan starting).
        val n = SignalLab.SR * 1200 / 1000
        val r = kotlin.random.Random(3)
        val pumping = DoubleArray(n) { i ->
            val t = i * 1000.0 / SignalLab.SR
            val amp = if (t < 600) 50.0 else 50.0 + (minOf(t - 600, 300.0) / 300.0) * 1450
            (r.nextDouble() * 2 - 1) * amp
        }
        assertEquals(0, runClap(ClapDetector(), pumping).size)
    }

    @Test
    fun suddenSustainedNoiseIsNotAClap() {
        // Tap/water/vacuum switching on: instant but long.
        val signal = concat(roomNoise(800), roomNoise(800, amplitude = 6_000.0, seed = 8))
        assertEquals(0, runClap(ClapDetector(), signal).size)
    }

    @Test
    fun noDetectionDuringWarmUpOrSilence() {
        assertEquals(0, runClap(ClapDetector(), SignalLab.silence(2_000)).size)
        // A clap in the first 200 ms (floor not learned yet) is ignored.
        assertEquals(0, runClap(ClapDetector(), withRoom(concat(roomNoise(60), clap()))).size)
    }

    @Test
    fun refractoryWindowSuppressesEcho() {
        val hits = runClap(
            ClapDetector(refractoryMs = 450),
            withRoom(concat(roomNoise(800), clap(), roomNoise(150), clap(seed = 4), roomNoise(500))),
        )
        assertEquals(1, hits.size)
    }

    @Test
    fun diagnosticsDescribeDecisionsWithoutAudio() {
        val messages = mutableListOf<String>()
        runClap(ClapDetector(onDiagnostic = { messages.add(it) }), withRoom(concat(roomNoise(800), clap(), roomNoise(300))))
        assertTrue(messages.any { it.startsWith("CLAP") })
        assertFalse(messages.any { it.length > 200 })
    }

    @Test
    fun invalidFrameLengthIsIgnored() {
        val result = ClapDetector().process(ShortArray(10), 11, 1_000L)
        assertFalse(result.clapDetected)
        assertEquals(0, result.clapCount)
    }
}
