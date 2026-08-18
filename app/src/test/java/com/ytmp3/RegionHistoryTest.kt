package com.ytmp3

import org.junit.Assert.assertEquals
import org.junit.Test

class RegionHistoryTest {
    @Test
    fun `undo restores prior regions`() {
        val regionA = SampleRegion(id = "a", startMs = 0, endMs = 100)
        val regionB = SampleRegion(id = "b", startMs = 100, endMs = 200)
        val history = RegionHistory(listOf(regionA))

        history.push(listOf(regionB))

        assertEquals(listOf(regionA), history.undo())
    }
}
