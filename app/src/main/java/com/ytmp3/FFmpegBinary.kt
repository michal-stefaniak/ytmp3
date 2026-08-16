package com.ytmp3

import android.content.Context
import java.io.File

data class FFmpegResult(val exitCode: Int, val stdout: ByteArray, val stderr: String)

object FFmpegBinary {

    fun binaryPath(context: Context): String =
        File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so").absolutePath

    /**
     * The ffmpeg module extracts its bundled shared libs here at app startup (see App.kt's
     * FFmpeg.getInstance().init() call); the python module does the same for its own package.
     * Some ffmpeg-bundled libraries (e.g. librubberband.so) depend on libc++_shared.so, which
     * ships in the *python* package's usr/lib, not ffmpeg's -- confirmed live on-device
     * ("CANNOT LINK EXECUTABLE ... library libc++_shared.so not found: needed by .../
     * librubberband.so"). YoutubeDL.kt's own init() concatenates both (plus aria2c's, unused
     * by this app) for exactly this reason when it shells out to ffmpeg internally, so this
     * does the same rather than only pointing at ffmpeg's own directory.
     */
    fun ldLibraryPath(context: Context): String {
        val packagesDir = File(context.noBackupFilesDir, "youtubedl-android/packages")
        return listOf("ffmpeg", "python").joinToString(":") { File(packagesDir, "$it/usr/lib").absolutePath }
    }

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
