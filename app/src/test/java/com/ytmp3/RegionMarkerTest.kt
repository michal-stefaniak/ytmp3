package com.ytmp3

import org.junit.Assert.assertEquals
import org.junit.Test

class RegionMarkerTest {
    @Test
    fun `clamps start below zero to zero`() {
        val (start, end) = RegionMarker.clamp(startMs = -500, endMs = 1000, trackDurationMs = 60_000)
        assertEquals(0L, start)
        assertEquals(1000L, end)
    }

    @Test
    fun `clamps end beyond track duration to track duration`() {
        val (start, end) = RegionMarker.clamp(startMs = 1000, endMs = 999_999, trackDurationMs = 60_000)
        assertEquals(1000L, start)
        assertEquals(60_000L, end)
    }

    @Test
    fun `enforces minimum region length by pushing end forward`() {
        val (start, end) = RegionMarker.clamp(startMs = 1000, endMs = 1010, trackDurationMs = 60_000, minLengthMs = 50)
        assertEquals(1000L, start)
        assertEquals(1050L, end)
    }

    @Test
    fun `minimum length push respects track duration ceiling`() {
        val (start, end) = RegionMarker.clamp(startMs = 59_980, endMs = 59_990, trackDurationMs = 60_000, minLengthMs = 50)
        assertEquals(59_950L, start)
        assertEquals(60_000L, end)
    }
}
