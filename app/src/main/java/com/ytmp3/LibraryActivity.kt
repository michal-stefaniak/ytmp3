package com.ytmp3

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.ytmp3.databinding.ActivityLibraryBinding

/** On-device sample browser. It only reads locally persisted projects and samples. */
class LibraryActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLibraryBinding
    private lateinit var db: ProjectDb
    private var shown = emptyList<LibraryRow>()
    private lateinit var adapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.title = "Sample library"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        db = ProjectDb.get(this)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf<String>())
        binding.lvSamples.adapter = adapter
        binding.etSearch.doAfterTextChanged { refresh() }
        binding.etTagFilter.doAfterTextChanged { refresh() }
        binding.cbFavourites.setOnCheckedChangeListener { _, _ -> refresh() }
        binding.lvSamples.setOnItemClickListener { _, _, position, _ ->
            val projectId = when (val row = shown[position]) {
                is LibraryRow.Project -> row.project.id
                is LibraryRow.Sample -> row.project.id
            }
            startActivity(Intent(this, SampleEditorActivity::class.java).putExtra(SampleEditorActivity.EXTRA_PROJECT_ID, projectId))
        }
        binding.lvSamples.setOnItemLongClickListener { _, _, position, _ ->
            (shown[position] as? LibraryRow.Sample)?.let { row ->
                db.upsertSample(row.sample.copy(favourite = !row.sample.favourite))
                refresh()
                true
            } ?: false
        }
        binding.btnBuildPack.setOnClickListener {
            val sampleIds = shown.mapNotNull { (it as? LibraryRow.Sample)?.sample?.id }
            if (sampleIds.isNotEmpty()) startActivity(PackBuilderActivity.intent(this, sampleIds))
        }
    }

    override fun onResume() { super.onResume(); refresh() }
    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun refresh() {
        val query = binding.etSearch.text.toString().trim().lowercase()
        val tag = binding.etTagFilter.text.toString().trim().lowercase()
        shown = LibraryBrowser.rows(db.listProjects(), db.listSamples(), query, tag, binding.cbFavourites.isChecked)
        adapter.clear()
        adapter.addAll(shown.map { row -> when (row) {
            is LibraryRow.Project -> "▸ ${row.project.title}"
            is LibraryRow.Sample -> {
                val favourite = if (row.sample.favourite) "★ " else ""
                "$favourite${row.sample.label.ifBlank { "Untitled sample" }}${row.sample.tags.takeIf { it.isNotEmpty() }?.joinToString(prefix = "  #") ?: ""}"
            }
        }
        })
        adapter.notifyDataSetChanged()
        binding.tvEmpty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
    }
}
