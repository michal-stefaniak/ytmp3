package com.ytmp3

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectModelsTest {
    @Test
    fun `recipe clamps fades to its region duration`() {
        assertEquals(250L, ProcessingRecipe(fadeInMs = 999).validated(250).fadeInMs)
    }

    @Test
    fun `recipe clamps negative fades to zero`() {
        assertEquals(0L, ProcessingRecipe(fadeOutMs = -1).validated(250).fadeOutMs)
    }

    @Test
    fun `region validation rejects overlap`() {
        assertFalse(
            SampleRegion.validateOrdered(
                listOf(SampleRegion("a", 0, 100), SampleRegion("b", 99, 200))
            )
        )
    }

    @Test
    fun `region validation permits adjacent valid regions`() {
        assertTrue(
            SampleRegion.validateOrdered(
                listOf(SampleRegion("a", 0, 100), SampleRegion("b", 100, 200))
            )
        )
    }

    @Test
    fun `region validation rejects invalid spans`() {
        assertFalse(SampleRegion.validateOrdered(listOf(SampleRegion("a", 100, 100))))
    }
}
