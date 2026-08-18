package com.ytmp3

object PeakMath {
    data class PeakBucket(val min: Short, val max: Short)

    fun reduceToPeaks(pcm: ShortArray, bucketCount: Int): List<PeakBucket> {
        if (pcm.isEmpty()) return emptyList()
        val effectiveBuckets = bucketCount.coerceAtMost(pcm.size)
        val baseSize = pcm.size / effectiveBuckets
        val remainder = pcm.size % effectiveBuckets

        val result = ArrayList<PeakBucket>(effectiveBuckets)
        var start = 0
        for (bucketIndex in 0 until effectiveBuckets) {
            val extra = if (bucketIndex == effectiveBuckets - 1) remainder else 0
            val end = start + baseSize + extra
            var min = pcm[start]
            var max = pcm[start]
            for (i in start until end) {
                if (pcm[i] < min) min = pcm[i]
                if (pcm[i] > max) max = pcm[i]
            }
            result.add(PeakBucket(min, max))
            start = end
        }
        return result
    }
}
