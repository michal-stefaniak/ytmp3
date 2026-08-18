package com.ytmp3

import org.junit.Assert.assertEquals
import org.junit.Test

class PeakMathTest {
    @Test
    fun `splits samples evenly into the requested bucket count`() {
        // 8 samples, 4 buckets -> 2 samples per bucket
        val pcm = shortArrayOf(0, 10, -5, 20, 100, -100, 3, 3)
        val peaks = PeakMath.reduceToPeaks(pcm, bucketCount = 4)

        assertEquals(4, peaks.size)
        assertEquals(PeakMath.PeakBucket(min = 0, max = 10), peaks[0])
        assertEquals(PeakMath.PeakBucket(min = -5, max = 20), peaks[1])
        assertEquals(PeakMath.PeakBucket(min = -100, max = 100), peaks[2])
        assertEquals(PeakMath.PeakBucket(min = 3, max = 3), peaks[3])
    }

    @Test
    fun `handles sample counts that dont divide evenly by folding the remainder into the last bucket`() {
        // 10 samples, 3 buckets -> 3,3,4
        val pcm = ShortArray(10) { it.toShort() }
        val peaks = PeakMath.reduceToPeaks(pcm, bucketCount = 3)

        assertEquals(3, peaks.size)
        assertEquals(PeakMath.PeakBucket(min = 0, max = 2), peaks[0])
        assertEquals(PeakMath.PeakBucket(min = 3, max = 5), peaks[1])
        assertEquals(PeakMath.PeakBucket(min = 6, max = 9), peaks[2])
    }

    @Test
    fun `returns one bucket per sample when there are fewer samples than requested buckets`() {
        val pcm = shortArrayOf(5, -5)
        val peaks = PeakMath.reduceToPeaks(pcm, bucketCount = 10)

        assertEquals(2, peaks.size)
        assertEquals(PeakMath.PeakBucket(min = 5, max = 5), peaks[0])
        assertEquals(PeakMath.PeakBucket(min = -5, max = -5), peaks[1])
    }

    @Test
    fun `empty input produces no buckets`() {
        assertEquals(emptyList<PeakMath.PeakBucket>(), PeakMath.reduceToPeaks(ShortArray(0), bucketCount = 100))
    }
}
