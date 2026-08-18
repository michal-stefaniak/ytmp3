package com.ytmp3

/** Decodes a raw little-endian s16 PCM byte stream without retaining the stream. */
class Pcm16LeDecoder {
    private var carry: Int? = null

    fun accept(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset, onSample: (Short) -> Unit) {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
        var index = offset
        val end = offset + length
        carry?.let { low ->
            if (index < end) {
                onSample(((bytes[index].toInt() and 0xff) shl 8 or low).toShort())
                index++
                carry = null
            }
        }
        while (index + 1 < end) {
            onSample(((bytes[index + 1].toInt() and 0xff) shl 8 or (bytes[index].toInt() and 0xff)).toShort())
            index += 2
        }
        if (index < end) carry = bytes[index].toInt() and 0xff
    }

    /** Returns false if the stream ended with an incomplete 16-bit sample. */
    fun finish(): Boolean = carry == null
}

/**
 * Bounded waveform reducer. With [expectedSampleCount], its buckets use the same exact,
 * equal-width-then-final-remainder partition as [PeakMath.reduceToPeaks]. With an unknown
 * length, it deterministically compacts adjacent buckets pairwise whenever the target is
 * exceeded. The latter keeps fewer than twice [bucketCount] accumulators and makes no promise
 * of equal final bucket widths.
 */
class StreamingPeakReducer(
    private val bucketCount: Int,
    private val expectedSampleCount: Long? = null
) {
    private data class Accumulator(var min: Short, var max: Short) {
        fun accept(sample: Short) {
            if (sample < min) min = sample
            if (sample > max) max = sample
        }

        fun merge(other: Accumulator) {
            accept(other.min)
            accept(other.max)
        }

        fun peak() = PeakMath.PeakBucket(min, max)
    }

    private val buckets = ArrayList<Accumulator>()
    private val effectiveBuckets: Int
    private val baseBucketSize: Long
    var acceptedSampleCount: Long = 0
        private set

    init {
        require(bucketCount > 0) { "bucketCount must be positive" }
        require(expectedSampleCount == null || expectedSampleCount >= 0) {
            "expectedSampleCount must not be negative"
        }
        effectiveBuckets = expectedSampleCount?.coerceAtMost(bucketCount.toLong())?.toInt() ?: bucketCount
        baseBucketSize = if (effectiveBuckets == 0) 0L else (expectedSampleCount ?: 0L) / effectiveBuckets
        if (effectiveBuckets > 0 && expectedSampleCount != null) {
            repeat(effectiveBuckets) { buckets += Accumulator(Short.MAX_VALUE, Short.MIN_VALUE) }
        }
    }

    fun accept(sample: Short) {
        if (expectedSampleCount != null && acceptedSampleCount < expectedSampleCount) {
            val bucketIndex = if (baseBucketSize == 0L) 0 else
                (acceptedSampleCount / baseBucketSize).coerceAtMost((effectiveBuckets - 1).toLong()).toInt()
            buckets[bucketIndex].accept(sample)
        } else if (expectedSampleCount == null) {
            buckets += Accumulator(sample, sample)
            compactIfNeeded()
        }
        acceptedSampleCount++
    }

    fun accept(samples: ShortArray, offset: Int = 0, length: Int = samples.size - offset) {
        require(offset >= 0 && length >= 0 && offset + length <= samples.size)
        for (index in offset until offset + length) accept(samples[index])
    }

    fun finish(): List<PeakMath.PeakBucket> = buckets
        .filter { it.min != Short.MAX_VALUE }
        .map { it.peak() }

    private fun compactIfNeeded() {
        if (buckets.size <= bucketCount) return
        val compacted = ArrayList<Accumulator>((buckets.size + 1) / 2)
        var index = 0
        while (index < buckets.size) {
            val merged = buckets[index]
            if (index + 1 < buckets.size) merged.merge(buckets[index + 1])
            compacted += merged
            index += 2
        }
        buckets.clear()
        buckets += compacted
    }
}
