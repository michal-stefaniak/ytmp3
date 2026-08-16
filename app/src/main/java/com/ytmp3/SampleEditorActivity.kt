package com.ytmp3

import android.media.MediaPlayer
import android.os.Bundle
import android.os.StatFs
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ytmp3.databinding.ActivitySampleEditorBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySampleEditorBinding.inflate(layoutInflater)
        setContentView(b.root)

        filePath = intent.getStringExtra("filePath") ?: run { finish(); return }
        title = intent.getStringExtra("title") ?: filePath.substringAfterLast('/')
        historyId = intent.getStringExtra("historyId")
        b.tvEditorTitle.text = title

        val mp = MediaPlayer().apply { setDataSource(filePath); prepare() }
        trackDurationMs = mp.duration.toLong()
        mp.release()

        b.waveform.onRegionsChanged = { regions -> b.btnExport.isEnabled = regions.isNotEmpty() }
        b.waveform.onRegionTapped = { region -> previewRegion(region) }
        b.btnExport.setOnClickListener { exportRegions() }

        loadWaveform()
    }

    private fun loadWaveform() {
        b.progressExtracting.visibility = android.view.View.VISIBLE
        lifecycleScope.launch {
            val result = WaveformExtractor.extract(this@SampleEditorActivity, filePath)
            b.progressExtracting.visibility = android.view.View.GONE
            result.fold(
                onSuccess = { peaks ->
                    b.waveform.visibility = android.view.View.VISIBLE
                    b.waveform.setPeaks(peaks, trackDurationMs)
                },
                onFailure = { showErrorDialog(it.message ?: "Failed to read audio") }
            )
        }
    }

    private fun previewRegion(region: RegionMarker) {
        previewJob?.cancel()
        previewPlayer?.release()
        val player = MediaPlayer().apply {
            setDataSource(filePath)
            prepare()
            seekTo(region.startMs.toInt())
            start()
        }
        previewPlayer = player
        previewJob = lifecycleScope.launch {
            while (player.currentPosition < region.endMs) delay(100)
            player.seekTo(region.startMs.toInt())
        }
    }

    private fun exportRegions() {
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

        b.btnExport.isEnabled = false
        lifecycleScope.launch {
            val exported = SampleExporter.export(this@SampleEditorActivity, filePath, title, regions)
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
                historyId?.let { HistoryDb.get(this).markSampled(it) }
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun showErrorDialog(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Error")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onDestroy() {
        previewJob?.cancel()
        previewPlayer?.release()
        super.onDestroy()
    }
}
