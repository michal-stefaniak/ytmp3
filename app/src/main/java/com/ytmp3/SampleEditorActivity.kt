package com.ytmp3

import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.widget.PopupMenu
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
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

    companion object {
        const val EXTRA_PROJECT_ID = "projectId"
        private const val MODE_SILENCE = 1
        private const val MODE_TRANSIENTS = 2
        private const val MODE_GRID = 3
        private const val GRID_SUBDIVISION = 4
    }

    private lateinit var b: ActivitySampleEditorBinding
    /** Original filesystem path or SAF document URI supplied by the download queue. */
    private lateinit var sourcePath: String
    /** Local source passed to ffmpeg and MediaPlayer; SAF documents are staged here. */
    private lateinit var workingFilePath: String
    private var stagedSourceFile: File? = null
    private lateinit var title: String
    private var historyId: String? = null
    private var trackDurationMs: Long = 0
    private var previewJob: Job? = null
    private var previewPlayer: MediaPlayer? = null
    private var previewRegionId: String? = null
    private var exporting = false
    private var projectId: String? = null
    private lateinit var regionHistory: RegionHistory

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySampleEditorBinding.inflate(layoutInflater)
        setContentView(b.root)

        val project = intent.getStringExtra(EXTRA_PROJECT_ID)?.let(ProjectDb.get(this)::getProject)
        projectId = project?.id
        regionHistory = RegionHistory(project?.regions ?: emptyList())
        sourcePath = project?.sourceUri ?: intent.getStringExtra("filePath") ?: run { finish(); return }
        title = project?.title ?: intent.getStringExtra("title") ?: sourcePath.substringAfterLast('/')
        historyId = intent.getStringExtra("historyId")
        b.tvEditorTitle.text = title

        b.waveform.setRegions(project?.regions.orEmpty().map { it.toMarker() })
        b.waveform.onRegionsChanged = { regions -> commitRegions(regions.map { it.toSampleRegion() }) }
        b.waveform.onRegionTapped = { region -> previewRegion(region) }
        b.btnExport.setOnClickListener { exportRegions() }
        b.btnSmartChop.setOnClickListener { showSmartChopMenu() }
        b.btnUndo.setOnClickListener { restoreHistory(regionHistory.undo()) }
        b.btnRedo.setOnClickListener { restoreHistory(regionHistory.redo()) }
        b.btnShowGrid.setOnClickListener { showBeatGrid() }
        updateRegionControls(regionHistory.current())

        loadWaveform()
    }

    private fun showSmartChopMenu() {
        PopupMenu(this, b.btnSmartChop).apply {
            menu.add(0, MODE_SILENCE, 0, "Silence")
            menu.add(0, MODE_TRANSIENTS, 1, "Transients")
            menu.add(0, MODE_GRID, 2, "Beat grid")
            setOnMenuItemClickListener(::applySmartChop)
            show()
        }
    }

    private fun applySmartChop(item: MenuItem): Boolean {
        if (trackDurationMs <= 0) return true
        val regions = when (item.itemId) {
            MODE_SILENCE -> SmartChop.bySilence(envelope(), .05f, 200L, peakDurationMs())
            MODE_TRANSIENTS -> SmartChop.byTransients(envelope(), .18f, 100L, peakDurationMs())
            MODE_GRID -> bpmOrNull()?.let { SmartChop.byGrid(trackDurationMs, it, GRID_SUBDIVISION) }
                ?: run {
                    Toast.makeText(this, "Enter a valid BPM before using beat-grid chop", Toast.LENGTH_SHORT).show()
                    return true
                }
            else -> return false
        }.map { (start, end) -> SampleRegion(startMs = start, endMs = end) }
        commitRegions(regions)
        return true
    }

    private fun showBeatGrid() {
        b.waveform.setBeatGrid(bpmOrNull(), GRID_SUBDIVISION)
    }

    private fun envelope(): List<Float> = b.waveform.currentPeaks().map { peak ->
        maxOf(kotlin.math.abs(peak.min.toInt()), kotlin.math.abs(peak.max.toInt())) / Short.MAX_VALUE.toFloat()
    }

    private fun peakDurationMs(): Long =
        (trackDurationMs / b.waveform.currentPeaks().size.coerceAtLeast(1)).coerceAtLeast(1L)

    private fun bpmOrNull(): Float? = b.inputBpm.text.toString().toFloatOrNull()
        ?.takeIf { it.isFinite() && it > 0f }

    private fun commitRegions(regions: List<SampleRegion>) {
        val ordered = regions.sortedBy { it.startMs }
        if (!SampleRegion.validateOrdered(ordered)) return
        regionHistory.push(ordered)
        b.waveform.setRegions(ordered.map { it.toMarker() })
        projectId?.let { ProjectDb.get(this).saveRegions(it, ordered) }
        updateRegionControls(ordered)
    }

    private fun restoreHistory(regions: List<SampleRegion>) {
        b.waveform.setRegions(regions.map { it.toMarker() })
        projectId?.let { ProjectDb.get(this).saveRegions(it, regions) }
        updateRegionControls(regions)
    }

    private fun updateRegionControls(regions: List<SampleRegion>) {
        b.btnExport.isEnabled = regions.isNotEmpty() && !exporting
        b.btnUndo.isEnabled = regionHistory.canUndo()
        b.btnRedo.isEnabled = regionHistory.canRedo()
    }

    private fun SampleRegion.toMarker() = RegionMarker(id = id, startMs = startMs, endMs = endMs)

    private fun RegionMarker.toSampleRegion() = SampleRegion(id = id, startMs = startMs, endMs = endMs)

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
            val result = runCatching {
                workingFilePath = withContext(Dispatchers.IO) { stageSourceIfNeeded() }
                WaveformExtractor.extract(this@SampleEditorActivity, workingFilePath).getOrThrow()
            }
            b.progressExtracting.visibility = android.view.View.GONE
            result.fold(
                onSuccess = { data ->
                    trackDurationMs = data.durationMs
                    b.waveform.visibility = android.view.View.VISIBLE
                    b.waveform.setPeaks(data.peaks, trackDurationMs)
                    b.waveform.setBeatGrid(bpmOrNull(), GRID_SUBDIVISION)
                },
                onFailure = { showErrorDialog(it.message ?: "Failed to read audio", finishOnDismiss = true) }
            )
        }
    }

    /**
     * FFmpeg and MediaPlayer consume paths, whereas a user-selected download directory returns a
     * SAF content URI. Stage only that URI into app cache; direct downloads continue to be read
     * in place. The temporary copy is removed in [onDestroy].
     */
    private fun stageSourceIfNeeded(): String {
        if (!sourcePath.startsWith("content://")) return sourcePath
        val staged = File.createTempFile("sample_source_", ".audio", cacheDir)
        try {
            contentResolver.openInputStream(Uri.parse(sourcePath))?.use { input ->
                staged.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("Couldn't read selected audio file")
            stagedSourceFile = staged
            return staged.absolutePath
        } catch (e: Exception) {
            staged.delete()
            throw e
        }
    }

    private fun previewRegion(region: RegionMarker) {
        if (previewRegionId == region.id) {
            stopPreview()
            return
        }
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
                        setDataSource(workingFilePath)
                        prepare()
                        seekTo(region.startMs.toInt())
                    }
                }
                val player = createdPlayer!!
                previewPlayer = player
                previewRegionId = region.id
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
                // If setup succeeded far enough to publish this player, the normal finally block
                // deliberately leaves it to stopPreview(). This is a real playback failure, so
                // release it here instead of retaining an unusable player/active-region marker.
                if (previewPlayer === createdPlayer) {
                    previewPlayer?.release()
                    previewPlayer = null
                    previewRegionId = null
                }
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
        previewRegionId = null
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

                val exported = SampleExporter.export(this@SampleEditorActivity, workingFilePath, title, regions)
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
                if (deleteOriginalSource()) {
                    historyId?.let {
                        HistoryDb.get(this).markSampled(it)
                        DownloadManager.removeItem(it)
                    }
                } else {
                    showErrorDialog("Couldn't delete the full track; your exported samples are safe.")
                }
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun deleteOriginalSource(): Boolean =
        if (sourcePath.startsWith("content://")) {
            DocumentFile.fromSingleUri(this, Uri.parse(sourcePath))?.delete() == true
        } else {
            File(sourcePath).delete()
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
        stagedSourceFile?.delete()
        stagedSourceFile = null
        super.onDestroy()
    }
}
