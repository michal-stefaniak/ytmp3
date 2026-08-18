package com.ytmp3

import android.content.Intent
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class ImportUrisTest {
    private val uriA = mockk<Uri>()
    private val uriB = mockk<Uri>()

    @Test
    fun `audio share intent yields every stream uri`() {
        val intent = mockk<Intent>()
        every { intent.action } returns Intent.ACTION_SEND_MULTIPLE
        @Suppress("DEPRECATION")
        every { intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) } returns arrayListOf(uriA, uriB)

        assertEquals(listOf(uriA, uriB), ImportUris.fromIntent(intent))
    }

    @Test
    fun `single audio share intent yields its stream uri`() {
        val intent = mockk<Intent>()
        every { intent.action } returns Intent.ACTION_SEND
        @Suppress("DEPRECATION")
        every { intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) } returns uriA

        assertEquals(listOf(uriA), ImportUris.fromIntent(intent))
    }
}
