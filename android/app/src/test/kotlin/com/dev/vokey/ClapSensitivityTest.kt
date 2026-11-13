package com.dev.vokey

import com.dev.vokey.SignalLab.clap
import com.dev.vokey.SignalLab.concat
import com.dev.vokey.SignalLab.roomNoise
import com.dev.vokey.SignalLab.runClap
import org.junit.Assert.assertEquals
import org.junit.Test

class ClapSensitivityTest {
    /** A distant/soft clap: ~22 dB above a quiet room. */
    private val softClap = concat(roomNoise(800), clap(amplitude = 900.0), roomNoise(400))

    @Test
    fun highSensitivityHearsASofterClapThanLow() {
        assertEquals(1, runClap(ClapDetector.forPercent(100), softClap).size)
        assertEquals(0, runClap(ClapDetector.forPercent(0), softClap).size)
    }

    @Test
    fun presetsMapOntoPercentages() {
        // "high" = 80 %, "low" = 20 %.
        val mediumClap = concat(roomNoise(800), clap(amplitude = 1_200.0), roomNoise(400))
        assertEquals(1, runClap(ClapDetector.forSensitivity("high"), mediumClap).size)
        assertEquals(0, runClap(ClapDetector.forSensitivity("low"), mediumClap).size)
    }
}
