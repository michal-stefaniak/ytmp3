package com.ytmp3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun `new edit after undo clears redo branch`() {
        val regionA = SampleRegion(id = "a", startMs = 0, endMs = 100)
        val regionB = SampleRegion(id = "b", startMs = 100, endMs = 200)
        val regionC = SampleRegion(id = "c", startMs = 200, endMs = 300)
        val history = RegionHistory(listOf(regionA))
        history.push(listOf(regionB))

        history.undo()
        history.push(listOf(regionC))

        assertFalse(history.canRedo())
        assertEquals(listOf(regionC), history.redo())
        assertTrue(history.canUndo())
    }
}
