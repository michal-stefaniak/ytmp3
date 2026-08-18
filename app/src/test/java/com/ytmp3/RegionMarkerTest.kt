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

    @Test
    fun `dragging start handle past its own end handle stops just before it instead of relocating the region`() {
        // Region [1000, 5000]; user drags the start handle to 6000 (past the end handle).
        val (start, end) = RegionMarker.clampDraggedStart(newStartMs = 6000, siblingEndMs = 5000, trackDurationMs = 60_000, minLengthMs = 50)
        assertEquals(4950L, start)
        assertEquals(5000L, end)
    }

    @Test
    fun `dragging start handle within range is unaffected`() {
        val (start, end) = RegionMarker.clampDraggedStart(newStartMs = 2000, siblingEndMs = 5000, trackDurationMs = 60_000, minLengthMs = 50)
        assertEquals(2000L, start)
        assertEquals(5000L, end)
    }

    @Test
    fun `dragging end handle before its own start handle stops just after it instead of relocating the region`() {
        // Region [1000, 5000]; user drags the end handle to 500 (before the start handle).
        val (start, end) = RegionMarker.clampDraggedEnd(newEndMs = 500, siblingStartMs = 1000, trackDurationMs = 60_000, minLengthMs = 50)
        assertEquals(1000L, start)
        assertEquals(1050L, end)
    }

    @Test
    fun `dragging end handle within range is unaffected`() {
        val (start, end) = RegionMarker.clampDraggedEnd(newEndMs = 8000, siblingStartMs = 1000, trackDurationMs = 60_000, minLengthMs = 50)
        assertEquals(1000L, start)
        assertEquals(8000L, end)
    }
}
