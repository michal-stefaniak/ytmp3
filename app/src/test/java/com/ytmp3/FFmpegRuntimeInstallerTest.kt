package com.ytmp3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

class FFmpegRuntimeInstallerTest {
    @Test
    fun `install replaces partial stale libraries and writes the current version marker`() {
        val root = Files.createTempDirectory("ffmpeg-runtime-test").toFile()
        val destination = File(root, "ffmpeg-runtime").apply { mkdirs() }
        File(destination, "libcrypto.so.3").writeText("stale")
        File(destination, AtomicRuntimeInstaller.versionMarkerName).writeText("old-version")

        val expected = mapOf(
            "libcrypto.so.3" to "current-crypto".toByteArray(),
            "libexpat.so.1" to "current-expat".toByteArray()
        )

        AtomicRuntimeInstaller.install(destination, "runtime-v2", expected.keys.toList()) { library ->
            ByteArrayInputStream(expected.getValue(library))
        }

        expected.forEach { (library, bytes) ->
            assertEquals(String(bytes), File(destination, library).readText())
            assertFalse(File(destination, "$library.tmp").exists())
        }
        assertEquals(
            "runtime-v2",
            File(destination, AtomicRuntimeInstaller.versionMarkerName).readText()
        )
    }
}
