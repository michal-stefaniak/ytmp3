package com.ytmp3

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object SampleExporter {

    /** Renders a locally imported sample for pack export. The original is never modified. */
    suspend fun renderPackSample(
        context: Context,
        source: String,
        destination: File,
        startMs: Long,
        endMs: Long,
        recipe: ProcessingRecipe,
        format: String,
        sampleRateHz: Int,
        bitDepth: Int
    ): Boolean = withContext(Dispatchers.IO) {
        val stagedSource = stageLocalSource(context, source)
        try {
            val result = FFmpegBinary.run(
                context,
                packRenderArgs(
                    stagedSource.absolutePath, destination.absolutePath, startMs, endMs, recipe,
                    format, sampleRateHz, bitDepth
                )
            )
            result.exitCode == 0 && destination.isFile && destination.length() > 0
        } finally {
            if (Uri.parse(source).scheme == "content") stagedSource.delete()
        }
    }

    /** Builds the ffmpeg recipe for a copied export; it never writes to the source. */
    fun packRenderArgs(
        inputPath: String,
        outputPath: String,
        startMs: Long,
        endMs: Long,
        recipe: ProcessingRecipe,
        format: String,
        sampleRateHz: Int,
        bitDepth: Int
    ): List<String> {
        require(endMs > startMs) { "Sample region must not be empty" }
        val durationMs = endMs - startMs
        val safeRecipe = recipe.validated(durationMs)
        val filters = buildList {
            if (safeRecipe.reverse) add("areverse")
            if (safeRecipe.normalise) add("loudnorm=I=-16:TP=-1.5:LRA=11")
            if (safeRecipe.fadeInMs > 0) add("afade=t=in:st=0:d=${seconds(safeRecipe.fadeInMs)}")
            if (safeRecipe.fadeOutMs > 0) {
                add("afade=t=out:st=${seconds(durationMs - safeRecipe.fadeOutMs)}:d=${seconds(safeRecipe.fadeOutMs)}")
            }
        }
        return buildList {
            add("-n"); add("-ss"); add(seconds(startMs)); add("-t"); add(seconds(durationMs))
            add("-i"); add(inputPath)
            if (filters.isNotEmpty()) { add("-af"); add(filters.joinToString(",")) }
            if (safeRecipe.mono) { add("-ac"); add("1") }
            add("-ar"); add(sampleRateHz.toString())
            add("-c:a")
            when (format.uppercase()) {
                "WAV" -> add(if (bitDepth >= 24) "pcm_s24le" else "pcm_s16le")
                "FLAC" -> {
                    add("flac")
                    add("-sample_fmt")
                    add(if (bitDepth >= 24) "s32" else "s16")
                    add("-bits_per_raw_sample")
                    add(if (bitDepth >= 24) "24" else "16")
                }
                else -> error("Unsupported export format")
            }
            add(outputPath)
        }
    }

    private fun seconds(milliseconds: Long): String = (milliseconds / 1000.0).toString()

    private fun stageLocalSource(context: Context, source: String): File {
        val direct = File(source)
        if (direct.isFile) return direct
        val uri = Uri.parse(source)
        require(uri.scheme == "content") { "Sample source is unavailable" }
        return File.createTempFile("pack_source_", ".audio", context.cacheDir).also { staged ->
            context.contentResolver.openInputStream(uri)?.use { input ->
                staged.outputStream().use { input.copyTo(it) }
            } ?: throw IllegalStateException("Couldn't open sample source")
        }
    }
}
