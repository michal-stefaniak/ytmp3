package com.ytmp3

import android.media.MediaPlayer
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ytmp3.databinding.ActivitySampleEditorBinding
import kotlinx.coroutines.CancellationException
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
        // MediaPlayer.prepare() blocks (unlike prepareAsync()) parsing/buffering the container --
        // on a large file or slow storage this can stall the main thread inside a touch-tap
        // callback, risking a visible freeze or ANR. Matches the Dispatchers.IO pattern
        // loadWaveform() already uses for its own blocking setup work.
        var createdPlayer: MediaPlayer? = null
        previewJob = lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    createdPlayer = MediaPlayer().apply {
                        setDataSource(filePath)
                        prepare()
                        seekTo(region.startMs.toInt())
                    }
                }
                val player = createdPlayer!!
                previewPlayer = player
                player.start()
                // Loops the region continuously (per the design spec) until stopPreview() cancels
                // this job -- delay() is a cancellable suspension point, so cancellation unwinds
                // the loop cleanly without needing the player to be touched after release().
                //
                // The inner wait is a do-while (always delay(100) at least once) rather than a
                // while-condition: seekTo() is asynchronous, so right after re-seeking to the
                // region start, player.currentPosition can still briefly report the stale
                // pre-seek (past-end) value. A plain `while (currentPosition < endMs) delay(100)`
                // would then evaluate false on entry and skip its own delay, so the outer loop
                // immediately re-seeks/re-starts again -- a tight zero-delay spin on the Main
                // dispatcher until the native position catches up.
                while (true) {
                    do { delay(100) } while (player.currentPosition < region.endMs)
                    player.seekTo(region.startMs.toInt())
                    if (!player.isPlaying) player.start()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showErrorDialog(e.message ?: "Couldn't play preview")
            } finally {
                // Either this job never got to publish itself as the active preview player (it was
                // cancelled by a newer tap's stopPreview() while still preparing on IO, or
                // setDataSource/prepare threw) -- in which case createdPlayer is orphaned and must
                // be released here -- or it did, in which case stopPreview() owns releasing it via
                // previewPlayer and this is a no-op.
                if (previewPlayer !== createdPlayer) createdPlayer?.release()
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

        exporting = true
        b.btnExport.isEnabled = false
        lifecycleScope.launch {
            try {
                if (Prefs.storageWarn) {
                    val estimatedBytes = regions.sumOf { (it.endMs - it.startMs) * 176L } // ~176 bytes/ms for 16-bit stereo 44.1kHz WAV
                    // free == null means the destination's free space couldn't be determined (e.g.
                    // the SAF provider doesn't expose it, or didn't answer in time) -- skip the
                    // precheck rather than reporting a number from the wrong volume.
                    val free = StorageUtil.availableBytes(
                        this@SampleEditorActivity,
                        Prefs.downloadDirUri,
                        getExternalFilesDir(null) ?: cacheDir
                    )
                    if (free != null && free < estimatedBytes + 50L * 1024 * 1024) {
                        showErrorDialog("Low storage: need ~${estimatedBytes / 1024 / 1024}MB free for WAV export")
                        return@launch
                    }
                }

                val exported = SampleExporter.export(this@SampleEditorActivity, filePath, title, regions)
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
            } finally {
                exporting = false
                b.btnExport.isEnabled = true
            }
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
