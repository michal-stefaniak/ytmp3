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
    private var shown = emptyList<SampleRecord>()
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
            startActivity(Intent(this, SampleEditorActivity::class.java).putExtra(SampleEditorActivity.EXTRA_PROJECT_ID, shown[position].projectId))
        }
        binding.lvSamples.setOnItemLongClickListener { _, _, position, _ ->
            val sample = shown[position]
            db.upsertSample(sample.copy(favourite = !sample.favourite))
            refresh()
            true
        }
        binding.btnBuildPack.setOnClickListener {
            if (shown.isNotEmpty()) startActivity(PackBuilderActivity.intent(this, shown.map { it.id }))
        }
    }

    override fun onResume() { super.onResume(); refresh() }
    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun refresh() {
        val query = binding.etSearch.text.toString().trim().lowercase()
        val tag = binding.etTagFilter.text.toString().trim().lowercase()
        val projectTitles = db.listProjects().associate { it.id to it.title }
        shown = db.listSamples().filter { sample ->
            val matchesSearch = query.isEmpty() || sample.label.lowercase().contains(query) ||
                sample.tags.any { it.lowercase().contains(query) } || projectTitles[sample.projectId].orEmpty().lowercase().contains(query)
            matchesSearch && (tag.isEmpty() || sample.tags.any { it.equals(tag, ignoreCase = true) }) &&
                (!binding.cbFavourites.isChecked || sample.favourite)
        }
        adapter.clear()
        adapter.addAll(shown.map { sample ->
            val source = projectTitles[sample.projectId].orEmpty()
            val favourite = if (sample.favourite) "★ " else ""
            "$favourite${sample.label.ifBlank { "Untitled sample" }}  •  $source${sample.tags.takeIf { it.isNotEmpty() }?.joinToString(prefix = "  #") ?: ""}"
        })
        adapter.notifyDataSetChanged()
        binding.tvEmpty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
    }
}
