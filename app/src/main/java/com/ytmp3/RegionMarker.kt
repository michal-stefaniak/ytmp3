package com.ytmp3

import java.util.UUID

data class RegionMarker(
    val id: String = UUID.randomUUID().toString(),
    val startMs: Long,
    val endMs: Long,
    val label: String = ""
) {
    companion object {
        const val MIN_LENGTH_MS = 50L

        fun clamp(
            startMs: Long,
            endMs: Long,
            trackDurationMs: Long,
            minLengthMs: Long = MIN_LENGTH_MS
        ): Pair<Long, Long> {
            var s = startMs.coerceIn(0, trackDurationMs)
            var e = endMs.coerceIn(0, trackDurationMs)
            if (e - s < minLengthMs) {
                e = (s + minLengthMs).coerceAtMost(trackDurationMs)
                s = (e - minLengthMs).coerceAtLeast(0)
            }
            return s to e
        }

        /**
         * Clamps a dragged start-handle position so the drag stops at (or just before) its own
         * end handle, rather than being handed straight to clamp() -- which, given a start >= end,
         * treats that as a degenerate span and silently relocates the *whole* region to a new
         * short window instead of stopping the drag at the sibling handle.
         */
        fun clampDraggedStart(
            newStartMs: Long,
            siblingEndMs: Long,
            trackDurationMs: Long,
            minLengthMs: Long = MIN_LENGTH_MS
        ): Pair<Long, Long> =
            clamp(newStartMs.coerceAtMost(siblingEndMs - minLengthMs), siblingEndMs, trackDurationMs, minLengthMs)

        /** Same as [clampDraggedStart], but for a dragged end handle relative to its sibling start. */
        fun clampDraggedEnd(
            newEndMs: Long,
            siblingStartMs: Long,
            trackDurationMs: Long,
            minLengthMs: Long = MIN_LENGTH_MS
        ): Pair<Long, Long> =
            clamp(siblingStartMs, newEndMs.coerceAtLeast(siblingStartMs + minLengthMs), trackDurationMs, minLengthMs)
    }
}
