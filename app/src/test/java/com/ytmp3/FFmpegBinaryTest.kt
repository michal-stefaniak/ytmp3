package com.ytmp3

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class FFmpegBinaryTest {
    @Test
    fun `binaryPath points at libffmpeg dot so in the native library dir`() {
        val ctx = mockk<Context>()
        val appInfo = mockk<android.content.pm.ApplicationInfo>()
        appInfo.nativeLibraryDir = "/data/app/com.ytmp3/lib/arm64"
        every { ctx.applicationInfo } returns appInfo

        assertEquals(
            "/data/app/com.ytmp3/lib/arm64/libffmpeg.so",
            FFmpegBinary.binaryPath(ctx)
        )
    }

    @Test
    fun `ldLibraryPath includes app native libraries before the retained ffmpeg libraries`() {
        val ctx = mockk<Context>()
        val appInfo = mockk<android.content.pm.ApplicationInfo>()
        appInfo.nativeLibraryDir = "/data/app/com.ytmp3/lib/x86_64"
        every { ctx.applicationInfo } returns appInfo
        every { ctx.noBackupFilesDir } returns File("/data/data/com.ytmp3/no_backup")

        assertEquals(
            "/data/app/com.ytmp3/lib/x86_64:/data/data/com.ytmp3/no_backup/ffmpeg-runtime:/data/data/com.ytmp3/no_backup/youtubedl-android/packages/ffmpeg/usr/lib",
            FFmpegBinary.ldLibraryPath(ctx)
        )
    }
}
