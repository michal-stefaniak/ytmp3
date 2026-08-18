package com.ytmp3

import org.junit.Assert.assertEquals
import org.junit.Test

class OutputNamesTest {
    @Test fun `collision appends sequence suffix`() {
        assertEquals("Kick_02.wav", OutputNames.unique("Kick.wav", setOf("Kick.wav", "Kick_01.wav")))
    }
}
