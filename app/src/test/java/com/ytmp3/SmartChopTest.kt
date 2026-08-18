package com.ytmp3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartChopTest {
    @Test
    fun `silence creates regions around quiet gaps`() {
        assertEquals(
            listOf(0L to 100L, 200L to 300L),
            SmartChop.bySilence(listOf(1f, 0f, 1f), .1f, 50, 100)
        )
    }

    @Test
    fun `grid at 120 bpm has 500ms quarter notes`() {
        assertEquals(listOf(0L to 500L, 500L to 1000L), SmartChop.byGrid(1000, 120f, 1))
    }

    @Test
    fun `transients respect minimum spacing`() {
        assertEquals(
            listOf(0L to 200L, 200L to 400L),
            SmartChop.byTransients(listOf(0f, 1f, 0f, 1f), .5f, 150, 100)
        )
    }

    @Test
    fun `streaming reducer keeps exact boundaries when expected sample count is known`() {
        val reducer = StreamingPeakReducer(bucketCount = 3, expectedSampleCount = 10)
        shortArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9).forEach(reducer::accept)

        assertEquals(
            listOf(
                PeakMath.PeakBucket(0, 2),
                PeakMath.PeakBucket(3, 5),
                PeakMath.PeakBucket(6, 9)
            ),
            reducer.finish()
        )
    }

    @Test
    fun `pcm decoder preserves samples across odd byte chunks`() {
        val decoder = Pcm16LeDecoder()
        val samples = mutableListOf<Short>()
        decoder.accept(byteArrayOf(0x34, 0x12, 0xFE.toByte()), onSample = samples::add)
        decoder.accept(byteArrayOf(0xFF.toByte()), onSample = samples::add)

        assertEquals(listOf(0x1234.toShort(), (-2).toShort()), samples)
        assertTrue(decoder.finish())
    }

    @Test
    fun `pcm decoder flags a truncated final sample`() {
        val decoder = Pcm16LeDecoder()
        decoder.accept(byteArrayOf(1), onSample = {})

        assertTrue(!decoder.finish())
    }
}
