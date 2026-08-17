package com.ytmp3

import android.media.MediaPlayer
import android.os.Bundle
import android.os.StatFs
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ytmp3.databinding.ActivitySampleEditorBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class SampleEditorActivity : AppCompatActivity() {

    private lateinit var b: ActivitySampleEditorBinding
    private lateinit var filePath: String
    private lateinit var title: String
    private var historyId: String? = null
    private var trackDurationMs: Long = 0
    private var previewJob: Job? = null
    private var previewPlayer: MediaPlayer? = null
    private var exporting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySampleEditorBinding.inflate(layoutInflater)
        setContentView(b.root)

        filePath = intent.getStringExtra("filePath") ?: run { finish(); return }
        title = intent.getStringExtra("title") ?: filePath.substringAfterLast('/')
        historyId = intent.getStringExtra("historyId")
        b.tvEditorTitle.text = title

        b.waveform.onRegionsChanged = { regions -> b.btnExport.isEnabled = regions.isNotEmpty() && !exporting }
        b.waveform.onRegionTapped = { region -> previewRegion(region) }
        b.btnExport.setOnClickListener { exportRegions() }

        loadWaveform()
    }

    private fun loadWaveform() {
        b.progressExtracting.visibility = android.view.View.VISIBLE
        lifecycleScope.launch {
            // Duration comes from WaveformExtractor's own ffmpeg decode (sample count / sample
            // rate) rather than from a separate probe like MediaMetadataRetriever. Using a second,
            // unreconciled duration source here would let WaveformView's ms-per-peak-bucket
            // (derived from that duration) drift out of sync with SampleExporter's ffmpeg -ss/-to
            // cuts (which operate on the real file's own timescale), silently shifting every
            // exported region. It also avoids hard-gating the whole feature on
            // MediaMetadataRetriever successfully reporting a duration -- a known failure mode for
            // MP3s with embedded thumbnail/metadata (which every download from this app has) that
            // ffmpeg itself can often still decode fine.
            val result = WaveformExtractor.extract(this@SampleEditorActivity, filePath)
            b.progressExtracting.visibility = android.view.View.GONE
            result.fold(
                onSuccess = { data ->
                    trackDurationMs = data.durationMs
                    b.waveform.visibility = android.view.View.VISIBLE
                    b.waveform.setPeaks(data.peaks, trackDurationMs)
                },
                onFailure = { showErrorDialog(it.message ?: "Failed to read audio", finishOnDismiss = true) }
            )
        }
    }

    private fun previewRegion(region: RegionMarker) {
        stopPreview()
        val player = try {
            MediaPlayer().apply {
                setDataSource(filePath)
                prepare()
                seekTo(region.startMs.toInt())
                start()
            }
        } catch (e: Exception) {
            showErrorDialog(e.message ?: "Couldn't play preview")
            return
        }
        previewPlayer = player
        previewJob = lifecycleScope.launch {
            // Loops the region continuously (per the design spec) until stopPreview() cancels
            // this job -- delay() is a cancellable suspension point, so cancellation unwinds the
            // loop cleanly without needing the player to be touched after release().
            while (true) {
                while (player.currentPosition < region.endMs) delay(100)
                player.seekTo(region.startMs.toInt())
                if (!player.isPlaying) player.start()
            }
        }
    }

    private fun stopPreview() {
        previewJob?.cancel()
        previewJob = null
        previewPlayer?.release()
        previewPlayer = null
    }

    private fun exportRegions() {
        if (exporting) return
        val regions = b.waveform.currentRegions()
        if (regions.isEmpty()) return

        if (Prefs.storageWarn) {
            val free = StatFs(cacheDir.absolutePath).availableBytes
            val estimatedBytes = regions.sumOf { (it.endMs - it.startMs) * 176L } // ~176 bytes/ms for 16-bit stereo 44.1kHz WAV
            if (free < estimatedBytes + 50L * 1024 * 1024) {
                showErrorDialog("Low storage: need ~${estimatedBytes / 1024 / 1024}MB free for WAV export")
                return
            }
        }

        exporting = true
        b.btnExport.isEnabled = false
        lifecycleScope.launch {
            val exported = SampleExporter.export(this@SampleEditorActivity, filePath, title, regions)
            exporting = false
            b.btnExport.isEnabled = true
            if (exported.isEmpty()) {
                showErrorDialog("Export failed for all regions")
                return@launch
            }
            val parentId = historyId ?: UUID.randomUUID().toString()
            exported.forEach { sample ->
                HistoryDb.get(this@SampleEditorActivity).insertSample(
                    id = UUID.randomUUID().toString(),
                    url = "",
                    title = "sample of $title",
                    parentId = parentId,
                    filePath = sample.filePath
                )
            }
            askKeepFullTrack()
        }
    }

    private fun askKeepFullTrack() {
        AlertDialog.Builder(this)
            .setTitle("Keep full track too?")
            .setMessage("The full downloaded track can be deleted now that your samples are exported, or kept alongside them.")
            .setPositiveButton("Keep") { _, _ -> finish() }
            .setNegativeButton("Delete") { _, _ ->
                File(filePath).delete()
                historyId?.let {
                    HistoryDb.get(this).markSampled(it)
                    DownloadManager.removeItem(it)
                }
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun showErrorDialog(message: String, finishOnDismiss: Boolean = false) {
        AlertDialog.Builder(this)
            .setTitle("Error")
            .setMessage(message)
            .setPositiveButton("OK") { _, _ -> if (finishOnDismiss) finish() }
            .show()
    }

    override fun onStop() {
        // Preview audio must not keep playing once the activity leaves the foreground -- onDestroy
        // alone isn't enough since the system can leave a backgrounded activity alive indefinitely.
        stopPreview()
        super.onStop()
    }

    override fun onDestroy() {
        stopPreview()
        super.onDestroy()
    }
}
