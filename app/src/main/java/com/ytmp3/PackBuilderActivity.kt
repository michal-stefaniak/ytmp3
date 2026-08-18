package com.ytmp3

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
        binding.btnMoveUp.setOnClickListener { moveSelected(-1) }
        binding.btnMoveDown.setOnClickListener { moveSelected(1) }
        binding.btnSavePack.setOnClickListener { savePack() }
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

    private fun savePack() {
        val name = binding.etPackName.text.toString().trim()
        if (name.isEmpty()) { binding.etPackName.error = "Enter a pack name"; return }
        if (orderedSampleIds.isEmpty()) { Toast.makeText(this, "Add at least one sample", Toast.LENGTH_SHORT).show(); return }
        val pack = SamplePack(id = UUID.randomUUID().toString(), name = name, sampleIds = orderedSampleIds.toList())
        db.upsertPack(pack)
        // Validate the template now so Task 6 can consume this persisted order without changing names here.
        PackExportRequest(pack, orderedSampleIds.map { samplesById.getValue(it) }, FilenameTemplate(binding.etFilenameTemplate.text.toString().ifBlank { "{label}_{n}" }))
        Toast.makeText(this, "Pack saved", Toast.LENGTH_SHORT).show()
        finish()
    }
}
