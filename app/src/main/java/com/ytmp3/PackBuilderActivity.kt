package com.ytmp3

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.ytmp3.databinding.ActivityPackBuilderBinding
import java.util.UUID

/** Curates a saved, ordered pack. Rendering it is intentionally deferred to Task 6. */
class PackBuilderActivity : AppCompatActivity() {
    companion object {
        private const val EXTRA_SAMPLE_IDS = "sample_ids"
        fun intent(context: Context, sampleIds: List<String>) = Intent(context, PackBuilderActivity::class.java)
            .putStringArrayListExtra(EXTRA_SAMPLE_IDS, ArrayList(sampleIds))
    }

    private lateinit var binding: ActivityPackBuilderBinding
    private lateinit var db: ProjectDb
    private lateinit var samplesById: Map<String, SampleRecord>
    private lateinit var adapter: ArrayAdapter<String>
    private val orderedSampleIds = mutableListOf<String>()
    private var currentPackId: String? = null
    private val chooseDestination = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) exportTo(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPackBuilderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.title = "Pack builder"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        db = ProjectDb.get(this)
        samplesById = db.listSamples().associateBy { it.id }
        orderedSampleIds += savedInstanceState?.getStringArrayList(EXTRA_SAMPLE_IDS)
            ?: intent.getStringArrayListExtra(EXTRA_SAMPLE_IDS).orEmpty()
        orderedSampleIds.retainAll(samplesById.keys)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_single_choice, mutableListOf<String>())
        binding.lvPackSamples.adapter = adapter
        binding.etFilenameTemplate.setText("{label}_{n}")
        binding.spExportFormat.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("WAV", "FLAC"))
        binding.btnMoveUp.setOnClickListener { moveSelected(-1) }
        binding.btnMoveDown.setOnClickListener { moveSelected(1) }
        binding.btnSavePack.setOnClickListener { savePack() }
        binding.btnExportPack.setOnClickListener {
            if (savePack(finishAfterSave = false)) chooseDestination.launch(null)
        }
        refreshList()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList(EXTRA_SAMPLE_IDS, ArrayList(orderedSampleIds))
        super.onSaveInstanceState(outState)
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun moveSelected(delta: Int) {
        val selected = binding.lvPackSamples.checkedItemPosition
        val target = selected + delta
        if (selected !in orderedSampleIds.indices || target !in orderedSampleIds.indices) return
        orderedSampleIds[selected] = orderedSampleIds[target].also { orderedSampleIds[target] = orderedSampleIds[selected] }
        refreshList(target)
    }

    private fun refreshList(selected: Int = binding.lvPackSamples.checkedItemPosition) {
        adapter.clear()
        adapter.addAll(orderedSampleIds.mapIndexed { index, id ->
            val sample = samplesById.getValue(id)
            "%02d  %s".format(index + 1, sample.label.ifBlank { "Untitled sample" })
        })
        adapter.notifyDataSetChanged()
        if (selected in orderedSampleIds.indices) binding.lvPackSamples.setItemChecked(selected, true)
    }

    private fun savePack(finishAfterSave: Boolean = true): Boolean {
        val name = binding.etPackName.text.toString().trim()
        if (name.isEmpty()) { binding.etPackName.error = "Enter a pack name"; return false }
        if (orderedSampleIds.isEmpty()) { Toast.makeText(this, "Add at least one sample", Toast.LENGTH_SHORT).show(); return false }
        val pack = SamplePack(
            id = currentPackId ?: UUID.randomUUID().toString(),
            name = name,
            sampleIds = orderedSampleIds.toList(),
            destinationUri = currentPackId?.let { db.getPack(it)?.destinationUri },
            format = binding.spExportFormat.selectedItem.toString(),
            zip = binding.cbZipExport.isChecked
        )
        currentPackId = pack.id
        db.upsertPack(pack)
        // Validate the template now so Task 6 can consume this persisted order without changing names here.
        PackExportRequest(pack, orderedSampleIds.map { samplesById.getValue(it) }, FilenameTemplate(binding.etFilenameTemplate.text.toString().ifBlank { "{label}_{n}" }))
        Toast.makeText(this, "Pack saved", Toast.LENGTH_SHORT).show()
        if (finishAfterSave) finish()
        return true
    }

    private fun exportTo(destination: Uri) {
        contentResolver.takePersistableUriPermission(
            destination,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        val packId = currentPackId ?: return
        val pack = db.getPack(packId) ?: return
        db.upsertPack(pack.copy(destinationUri = destination.toString()))
        val request = OneTimeWorkRequestBuilder<PackExportWorker>()
            .setInputData(workDataOf(
                PackExportWorker.INPUT_PACK_ID to packId,
                PackExportWorker.INPUT_DESTINATION_URI to destination.toString(),
                PackExportWorker.INPUT_FILENAME_TEMPLATE to binding.etFilenameTemplate.text.toString().ifBlank { "{label}_{n}" }
            ))
            .build()
        binding.btnExportPack.isEnabled = false
        WorkManager.getInstance(this).enqueueUniqueWork("pack-export-$packId", ExistingWorkPolicy.REPLACE, request)
        WorkManager.getInstance(this).getWorkInfoByIdLiveData(request.id).observe(this) { info ->
            when (info.state) {
                WorkInfo.State.RUNNING -> {
                    val done = info.progress.getInt(PackExportWorker.PROGRESS_COMPLETED, 0)
                    val total = info.progress.getInt(PackExportWorker.PROGRESS_TOTAL, 0)
                    binding.tvExportProgress.text = "Exporting $done of $total"
                }
                WorkInfo.State.SUCCEEDED -> {
                    binding.btnExportPack.isEnabled = true
                    binding.tvExportProgress.text = "Export complete"
                    info.outputData.getString(PackExportWorker.OUTPUT_URI)?.let { output ->
                        shareOutput(output, info.outputData.getBoolean(PackExportWorker.OUTPUT_IS_ZIP, false))
                    }
                }
                WorkInfo.State.FAILED -> {
                    binding.btnExportPack.isEnabled = true
                    binding.tvExportProgress.text = info.outputData.getString(PackExportWorker.OUTPUT_ERROR) ?: "Export failed"
                }
                else -> Unit
            }
        }
    }

    private fun shareOutput(output: String, isZip: Boolean) {
        val uri = Uri.parse(output)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = if (isZip) "application/zip" else "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Share sample pack"))
    }
}
