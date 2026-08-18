package com.ytmp3

import kotlin.math.min

object SmartChop {
    fun bySilence(
        envelope: List<Float>,
        threshold: Float,
        minGapMs: Long,
        bucketDurationMs: Long
    ): List<Pair<Long, Long>> {
        require(minGapMs >= 0 && bucketDurationMs > 0)
        if (envelope.isEmpty()) return emptyList()
        val regions = mutableListOf<Pair<Long, Long>>()
        var regionStart: Long? = null
        var quietStart: Long? = null
        envelope.forEachIndexed { index, value ->
            val start = index * bucketDurationMs
            if (value <= threshold) {
                if (quietStart == null) quietStart = start
                if (regionStart != null && start + bucketDurationMs - quietStart!! >= minGapMs) {
                    regions += regionStart!! to quietStart!!
                    regionStart = null
                }
            } else {
                if (regionStart == null) regionStart = start
                quietStart = null
            }
        }
        regionStart?.let { regions += it to envelope.size * bucketDurationMs }
        return regions.filter { it.second > it.first }
    }

    fun byTransients(
        envelope: List<Float>,
        sensitivity: Float,
        minSpacingMs: Long,
        bucketDurationMs: Long
    ): List<Pair<Long, Long>> {
        require(minSpacingMs >= 0 && bucketDurationMs > 0)
        if (envelope.isEmpty()) return emptyList()
        val duration = envelope.size * bucketDurationMs
        val boundaries = mutableListOf(0L)
        var previous = envelope.first()
        envelope.drop(1).forEachIndexed { offset, value ->
            val boundary = (offset + 2L) * bucketDurationMs
            if (value - previous >= sensitivity && boundary - boundaries.last() >= minSpacingMs) {
                boundaries += boundary
            }
            previous = value
        }
        if (boundaries.last() != duration) boundaries += duration
        return boundaries.zipWithNext().filter { it.second > it.first }
    }

    fun byGrid(durationMs: Long, bpm: Float, subdivision: Int): List<Pair<Long, Long>> {
        require(durationMs >= 0) { "durationMs must not be negative" }
        require(bpm > 0f) { "bpm must be positive" }
        require(subdivision > 0) { "subdivision must be positive" }
        val step = (60_000f / bpm / subdivision).toLong().coerceAtLeast(50)
        return generateSequence(0L) { it + step }.takeWhile { it < durationMs }
            .map { it to min(it + step, durationMs) }.toList()
    }
}
