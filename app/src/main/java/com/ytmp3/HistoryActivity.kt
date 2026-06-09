package com.ytmp3

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ytmp3.databinding.ActivityHistoryBinding
import com.ytmp3.databinding.ItemHistoryBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val HISTORY_DIFF = object : DiffUtil.ItemCallback<HistoryRecord>() {
    override fun areItemsTheSame(a: HistoryRecord, b: HistoryRecord) = a.id == b.id
    override fun areContentsTheSame(a: HistoryRecord, b: HistoryRecord) = a == b
}

class HistoryActivity : AppCompatActivity() {

    private lateinit var b: ActivityHistoryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(b.root)
        supportActionBar?.title = "History"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val adapter = HistoryAdapter()
        b.rvHistory.adapter = adapter

        val records = HistoryDb.get(this).getAll()
        adapter.submitList(records)
        b.tvEmpty.visibility = if (records.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE

        b.btnClearHistory.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage("Clear all history?")
                .setPositiveButton("Clear") { _, _ ->
                    HistoryDb.get(this).clearAll()
                    adapter.submitList(emptyList())
                    b.tvEmpty.visibility = android.view.View.VISIBLE
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private val fmt = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())

    inner class HistoryAdapter : ListAdapter<HistoryRecord, HistoryAdapter.VH>(HISTORY_DIFF) {
        inner class VH(private val b: ItemHistoryBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(r: HistoryRecord) {
                b.tvHistoryTitle.text = r.title.ifBlank { r.url }
                b.tvHistoryMeta.text = "${fmt.format(Date(r.timestamp))} • ${r.status.lowercase()}"
                b.tvHistoryMeta.setTextColor(
                    b.root.context.getColor(
                        if (r.status == "ERROR") android.R.color.holo_red_light else android.R.color.darker_gray
                    )
                )
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )
        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    }
}
