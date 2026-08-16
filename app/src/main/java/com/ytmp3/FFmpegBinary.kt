package com.ytmp3

import android.content.Context
import java.io.File

data class FFmpegResult(val exitCode: Int, val stdout: ByteArray, val stderr: String)

object FFmpegBinary {

    fun binaryPath(context: Context): String =
        File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so").absolutePath

    /**
     * The ffmpeg module (io.github.junkfood02.youtubedl-android:ffmpeg) extracts its
     * bundled shared libs here at app startup (see App.kt's FFmpeg.getInstance().init()
     * call). YoutubeDL.kt's own init() independently derives the same path for yt-dlp's
     * internal ffmpeg calls, so this location is a stable, if undocumented, contract
     * between the two library modules rather than a version-specific implementation detail.
     */
    fun ldLibraryPath(context: Context): String =
        File(context.noBackupFilesDir, "youtubedl-android/packages/ffmpeg/usr/lib").absolutePath

    fun run(context: Context, args: List<String>): FFmpegResult {
        val command = mutableListOf(binaryPath(context)).apply { addAll(args) }
        val process = ProcessBuilder(command)
            .apply { environment()["LD_LIBRARY_PATH"] = ldLibraryPath(context) }
            .start()

        // Stdout can carry large binary output (raw PCM via pipe:1 in Task 3) while ffmpeg
        // concurrently logs progress to stderr. Reading either stream to completion before
        // touching the other risks a classic ProcessBuilder pipe deadlock once stderr fills
        // its ~64KB buffer while we're still blocked draining stdout — so both are drained
        // on separate threads at once.
        var stderrText = ""
        val stderrThread = Thread {
            stderrText = process.errorStream.bufferedReader().readText()
        }.apply { start() }

        val stdout = process.inputStream.readBytes()
        stderrThread.join()
        val exitCode = process.waitFor()
        return FFmpegResult(exitCode, stdout, stderrText)
    }
}
