package com.ytmp3

import android.content.Context
import android.os.Build
import java.io.File

/** Installs versioned FFmpeg support libraries from the APK before the binary is launched. */
object FFmpegRuntime {
    private const val assetDirectory = "ffmpeg-runtime"
    private const val installedDirectory = "ffmpeg-runtime"
    private val supportedAbis = setOf("arm64-v8a", "armeabi-v7a", "x86_64")
    private val libraries = listOf(
        "libandroid-posix-semaphore.so",
        "libandroid-support.so",
        "libcrypto.so.3",
        "libexpat.so.1"
    )

    fun libraryDirectory(context: Context): File =
        File(context.noBackupFilesDir, installedDirectory)

    fun install(context: Context) {
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it in supportedAbis }
            ?: throw IllegalStateException("No bundled FFmpeg runtime for ${Build.SUPPORTED_ABIS.joinToString()}")
        val destination = libraryDirectory(context).apply { mkdirs() }
        libraries.forEach { library ->
            val target = File(destination, library)
            if (!target.isFile) {
                context.assets.open("$assetDirectory/$abi/$library").use { input ->
                    target.outputStream().use(input::copyTo)
                }
            }
        }
    }
}
