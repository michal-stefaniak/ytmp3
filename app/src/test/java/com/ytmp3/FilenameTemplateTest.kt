package com.ytmp3

import org.junit.Assert.assertEquals
import org.junit.Test

class FilenameTemplateTest {
    @Test
    fun `template substitutes sequence and BPM`() {
        assertEquals("Kick_01_128.wav", FilenameTemplate("{label}_{n}_{bpm}.wav").render("Kick", 1, 128f, null))
    }

    @Test
    fun `template omits unavailable BPM and key`() {
        assertEquals("Pad_02_.wav", FilenameTemplate("{label}_{n}_{key}.wav").render("Pad", 2, null, null))
    }

    @Test
    fun `template substitutes musical key`() {
        assertEquals("Snare_F#_03", FilenameTemplate("{label}_{key}_{n}").render("Snare", 3, 120f, "F#"))
    }
}
