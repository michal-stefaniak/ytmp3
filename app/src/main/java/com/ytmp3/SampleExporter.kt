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
        try {
            regions.mapIndexedNotNull { index, region ->
                val safeTitle = sourceTitle.take(40).replace(Regex("[^A-Za-z0-9 _-]"), "_")
                val fileName = "${safeTitle}_${index + 1}.wav"
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
        context.contentResolver.openOutputStream(dest.uri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        }
        return dest.uri.toString()
    }
}
