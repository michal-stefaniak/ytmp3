package com.ytmp3

import java.util.UUID

data class RegionMarker(
    val id: String = UUID.randomUUID().toString(),
    val startMs: Long,
    val endMs: Long,
    val label: String = ""
) {
    companion object {
        fun clamp(
            startMs: Long,
            endMs: Long,
            trackDurationMs: Long,
            minLengthMs: Long = 50
        ): Pair<Long, Long> {
            var s = startMs.coerceIn(0, trackDurationMs)
            var e = endMs.coerceIn(0, trackDurationMs)
            if (e - s < minLengthMs) {
                e = (s + minLengthMs).coerceAtMost(trackDurationMs)
                s = (e - minLengthMs).coerceAtLeast(0)
            }
            return s to e
        }
    }
}
