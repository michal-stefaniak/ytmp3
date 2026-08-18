package com.ytmp3

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object SampleExporter {

    data class ExportedSample(val region: RegionMarker, val filePath: String)

    suspend fun export(
        context: Context,
        sourceFilePath: String,
        sourceTitle: String,
        regions: List<RegionMarker>
    ): List<ExportedSample> = withContext(Dispatchers.IO) {
        val tempDir = File(context.cacheDir, "sample_export_${System.currentTimeMillis()}").also { it.mkdirs() }
        val exportBatchId = System.currentTimeMillis()
        try {
            regions.mapIndexedNotNull { index, region ->
                // Each region's whole pipeline (ffmpeg cut + output copy) is independently
                // resilient: an exception anywhere here (e.g. disk-full during the output copy)
                // skips just this region rather than losing every already-exported region ahead
                // of it in the batch.
                try {
                    val safeTitle = sourceTitle.take(40).replace(Regex("[^A-Za-z0-9 _-]"), "_")
                    val fileName = "${safeTitle}_${exportBatchId}_${index + 1}.wav"
                    val tempOut = File(tempDir, fileName)

                    val result = FFmpegBinary.run(
                        context,
                        listOf(
                            "-i", sourceFilePath,
                            "-ss", (region.startMs / 1000.0).toString(),
                            "-to", (region.endMs / 1000.0).toString(),
                            "-c:a", "pcm_s16le",
                            tempOut.absolutePath
                        )
                    )
                    if (result.exitCode != 0 || !tempOut.exists()) return@mapIndexedNotNull null

                    val finalPath = moveToOutput(context, tempOut)
                    finalPath?.let { ExportedSample(region, it) }
                } catch (e: Exception) {
                    null
                }
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun moveToOutput(context: Context, file: File): String? {
        val dirUriStr = Prefs.downloadDirUri
        return if (dirUriStr != null) {
            copyToSamplesTree(context, file, Uri.parse(dirUriStr))
        } else {
            val destDir = File(context.getExternalFilesDir(null), "Samples").also { it.mkdirs() }
            file.copyTo(File(destDir, file.name), overwrite = true).absolutePath
        }
    }

    private fun copyToSamplesTree(context: Context, file: File, treeUri: Uri): String? {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        val samplesDir = root.findFile("Samples") ?: root.createDirectory("Samples") ?: return null
        val dest = samplesDir.createFile("audio/wav", file.nameWithoutExtension) ?: return null
        // openOutputStream can return null for some DocumentsProvider implementations; without this
        // check, a write that silently didn't happen would still report success to the caller.
        val written = context.contentResolver.openOutputStream(dest.uri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
            true
        } ?: false
        return if (written) dest.uri.toString() else null
    }

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
