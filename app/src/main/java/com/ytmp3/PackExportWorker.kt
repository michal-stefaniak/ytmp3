package com.ytmp3

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PackExportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        const val INPUT_PACK_ID = "pack_id"
        const val INPUT_DESTINATION_URI = "destination_uri"
        const val INPUT_FILENAME_TEMPLATE = "filename_template"
        const val PROGRESS_COMPLETED = "completed"
        const val PROGRESS_TOTAL = "total"
        const val OUTPUT_URI = "output_uri"
        const val OUTPUT_IS_ZIP = "output_is_zip"
        const val OUTPUT_ERROR = "error"
        private const val CHANNEL_ID = "pack_export"
        private const val NOTIFICATION_ID = 4106
    }

    override suspend fun doWork(): Result = runCatching {
        setForeground(createForegroundInfo("Preparing pack export"))
        val packId = inputData.getString(INPUT_PACK_ID) ?: error("Missing pack")
        val destinationUri = inputData.getString(INPUT_DESTINATION_URI) ?: error("Missing destination")
        val db = ProjectDb.get(applicationContext)
        val pack = db.getPack(packId) ?: error("Pack no longer exists")
        val samples = db.listSamples().associateBy { it.id }
        val ordered = pack.sampleIds.map { samples[it] ?: error("A pack sample is missing") }
        val projects = db.listProjects().associateBy { it.id }
        val filenameTemplate = FilenameTemplate(inputData.getString(INPUT_FILENAME_TEMPLATE) ?: "{label}_{n}")
        val root = DocumentFile.fromTreeUri(applicationContext, Uri.parse(destinationUri))
            ?: error("Output folder is unavailable")
        val usedNames = root.listFiles().mapNotNull { it.name }.toSet()
        val extension = if (pack.zip) ".zip" else ""
        val outputName = OutputNames.unique(safeName(pack.name).ifBlank { "Sample pack" } + extension, usedNames)
        val tempDir = File(applicationContext.cacheDir, "pack_export_${id}").also { it.mkdirs() }
        try {
            setProgress(workDataOf(PROGRESS_COMPLETED to 0, PROGRESS_TOTAL to ordered.size))
            val usedSampleNames = mutableSetOf<String>()
            val rendered = ordered.mapIndexed { index, sample ->
                val project = projects[sample.projectId]
                val baseName = safeName(filenameTemplate.render(
                    sample.label.ifBlank { "Sample" }, index + 1, project?.bpmOverride ?: project?.bpmEstimate, project?.keyEstimate
                )).ifBlank { "Sample_${"%02d".format(index + 1)}" }
                val name = OutputNames.unique("$baseName.${pack.format.lowercase()}", usedSampleNames)
                usedSampleNames += name
                val target = File(tempDir, name)
                check(SampleExporter.renderPackSample(
                    applicationContext, sample.outputUri, target, pack.format, pack.sampleRateHz, pack.bitDepth
                )) { "Couldn't render ${sample.label.ifBlank { "sample ${index + 1}" }}" }
                setProgress(workDataOf(PROGRESS_COMPLETED to index + 1, PROGRESS_TOTAL to ordered.size))
                setForeground(createForegroundInfo("Exporting ${index + 1} of ${ordered.size}"))
                target
            }
            val output = if (pack.zip) writeZip(root, outputName, rendered) else writeFolder(root, outputName, rendered)
            Result.success(workDataOf(OUTPUT_URI to output.toString(), OUTPUT_IS_ZIP to pack.zip))
        } finally {
            tempDir.deleteRecursively()
        }
    }.getOrElse { error ->
        Result.failure(workDataOf(OUTPUT_ERROR to (error.message ?: "Export failed")))
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = createForegroundInfo("Preparing pack export")

    private fun writeFolder(root: DocumentFile, name: String, files: List<File>): Uri {
        val folder = root.createDirectory(name) ?: error("Couldn't create output folder")
        files.forEach { file ->
            val destination = folder.createFile(mimeType(file), file.name)
                ?: error("Couldn't create ${file.name}")
            applicationContext.contentResolver.openOutputStream(destination.uri)?.use { output ->
                file.inputStream().use { it.copyTo(output) }
            } ?: error("Couldn't write ${file.name}")
        }
        return folder.uri
    }

    private fun writeZip(root: DocumentFile, name: String, files: List<File>): Uri {
        val destination = root.createFile("application/zip", name)
            ?: error("Couldn't create ZIP")
        applicationContext.contentResolver.openOutputStream(destination.uri)?.use { output ->
            ZipOutputStream(output).use { zip ->
                files.forEach { file ->
                    zip.putNextEntry(ZipEntry(file.name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        } ?: error("Couldn't write ZIP")
        return destination.uri
    }

    private fun createForegroundInfo(message: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Pack exports", NotificationManager.IMPORTANCE_LOW))
        }
        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Exporting sample pack")
            .setContentText(message)
            .setOngoing(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    private fun safeName(value: String): String = value.replace(Regex("[^A-Za-z0-9 _-]"), "_").trim()
    private fun mimeType(file: File): String = if (file.extension.equals("flac", true)) "audio/flac" else "audio/wav"
}
