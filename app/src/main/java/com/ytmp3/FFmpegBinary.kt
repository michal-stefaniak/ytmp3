package com.ytmp3

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File

data class FFmpegResult(val exitCode: Int, val stdout: ByteArray, val stderr: String)
data class FFmpegStreamResult(val exitCode: Int, val stderr: String, val bytesRead: Long)

object FFmpegBinary {

    fun binaryPath(context: Context): String =
        File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so").absolutePath

    /**
     * The retained ffmpeg artifact extracts its bundled shared libraries here at app startup
     * (see App.kt's FFmpeg.getInstance().init() call). This app has no downloader or Python
     * runtime, so only ffmpeg's own library directory is exposed to the child process.
     */
    fun ldLibraryPath(context: Context): String {
        return File(context.noBackupFilesDir, "youtubedl-android/packages/ffmpeg/usr/lib").absolutePath
    }

    /**
     * Runs ffmpeg and suspends until it exits. The actual work (readBytes()/Thread.join()/
     * waitFor()) is blocking with no suspension points of its own, so plain coroutine cancellation
     * has nothing to act on inside it -- a cancelled Job wouldn't stop the underlying process, e.g.
     * backing out of SampleEditorActivity mid-extraction/export would leave ffmpeg running to
     * completion regardless. Wrapping the blocking work in suspendCancellableCoroutine lets
     * invokeOnCancellation kill the child process via destroy() as soon as the calling coroutine
     * is cancelled, which unblocks the read/join/waitFor calls (they see EOF/process death) so the
     * background thread can still wind down instead of leaking.
     */
    suspend fun run(context: Context, args: List<String>): FFmpegResult = withContext(Dispatchers.IO) {
        val command = mutableListOf(binaryPath(context)).apply { addAll(args) }
        val process = ProcessBuilder(command)
            .apply { environment()["LD_LIBRARY_PATH"] = ldLibraryPath(context) }
            .start()

        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { process.destroy() }
            try {
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
                cont.resumeWith(Result.success(FFmpegResult(exitCode, stdout, stderrText)))
            } catch (e: Exception) {
                cont.resumeWith(Result.failure(e))
            }
        }
    }

    /** Streams ffmpeg stdout on Dispatchers.IO while stderr is drained concurrently. */
    suspend fun streamPcm(
        context: Context,
        args: List<String>,
        onChunk: (bytes: ByteArray, offset: Int, length: Int) -> Unit
    ): FFmpegStreamResult = withContext(Dispatchers.IO) {
        val command = mutableListOf(binaryPath(context)).apply { addAll(args) }
        val process = ProcessBuilder(command)
            .apply { environment()["LD_LIBRARY_PATH"] = ldLibraryPath(context) }
            .start()

        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { process.destroy() }
            var stderrText = ""
            var stderrThread: Thread? = null
            var bytesRead = 0L
            var exitCode: Int? = null
            var failure: Exception? = null
            try {
                stderrThread = Thread {
                    stderrText = process.errorStream.bufferedReader().readText()
                }.apply { start() }
                val buffer = ByteArray(8 * 1024)
                process.inputStream.use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read > 0) {
                            onChunk(buffer, 0, read)
                            bytesRead += read
                        }
                    }
                }
            } catch (e: Exception) {
                failure = e
                process.destroy()
            } finally {
                // A callback can throw while ffmpeg is still writing. Stop it before joining the
                // stderr reader so both pipes close; otherwise that reader may leak indefinitely.
                if (failure != null || !cont.isActive) process.destroy()
                try {
                    stderrThread?.join()
                    exitCode = process.waitFor()
                } catch (e: Exception) {
                    if (failure == null) failure = e
                }
            }
            if (!cont.isActive) return@suspendCancellableCoroutine
            failure?.let { error ->
                cont.resumeWith(
                    Result.failure(IllegalStateException("ffmpeg stream failed: $stderrText", error))
                )
            } ?: cont.resumeWith(
                Result.success(FFmpegStreamResult(exitCode ?: -1, stderrText, bytesRead))
            )
        }
    }
}
