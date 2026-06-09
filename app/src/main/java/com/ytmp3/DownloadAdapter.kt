package com.ytmp3

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ytmp3.databinding.ItemDownloadBinding

class DownloadAdapter(
    private val onRetry: (String) -> Unit,
    private val onErrorClick: (String) -> Unit,
    private val onPlayClick: (String) -> Unit,
    private val onLongClick: (String) -> Unit
) : ListAdapter<DownloadItem, DownloadAdapter.VH>(DIFF) {

    inner class VH(private val b: ItemDownloadBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: DownloadItem) {
            b.tvTitle.text = item.title.ifBlank { item.url }
            b.tvStatus.text = when (item.status) {
                DownloadStatus.QUEUED -> "queued"
                DownloadStatus.DOWNLOADING -> "${item.progress}%"
                DownloadStatus.DONE -> "done"
                DownloadStatus.ERROR -> "tap for details"
            }
            b.tvStatus.setTextColor(
                when (item.status) {
                    DownloadStatus.ERROR -> b.root.context.getColor(android.R.color.holo_red_light)
                    DownloadStatus.DONE -> 0xFF4CAF50.toInt()
                    else -> b.root.context.getColor(android.R.color.darker_gray)
                }
            )
            b.progress.progress = item.progress
            b.progress.isIndeterminate = item.status == DownloadStatus.DOWNLOADING && item.progress == 0
            b.progress.visibility = if (item.status == DownloadStatus.DONE) View.INVISIBLE else View.VISIBLE
            b.btnRetry.visibility = if (item.status == DownloadStatus.ERROR) View.VISIBLE else View.GONE
            b.btnRetry.setOnClickListener { onRetry(item.id) }
            b.root.setOnClickListener {
                when (item.status) {
                    DownloadStatus.ERROR -> onErrorClick(item.errorMsg ?: "Unknown error")
                    DownloadStatus.DONE -> item.filePath?.let { onPlayClick(it) }
                    else -> {}
                }
            }
            b.root.setOnLongClickListener { onLongClick(item.url); true }
            val etaText = if (Prefs.showSpeed && item.status == DownloadStatus.DOWNLOADING && item.etaSeconds > 0) {
                val spd = if (item.speedKbps >= 1024) "${item.speedKbps / 1024}MB/s" else "${item.speedKbps}KB/s"
                "$spd • ETA ${item.etaSeconds}s"
            } else ""
            b.tvSpeed.text = etaText
            b.tvSpeed.visibility = if (etaText.isNotEmpty()) View.VISIBLE else View.GONE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        ItemDownloadBinding.inflate(LayoutInflater.from(parent.context), parent, false)
    )

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<DownloadItem>() {
            override fun areItemsTheSame(a: DownloadItem, b: DownloadItem) = a.id == b.id
            override fun areContentsTheSame(a: DownloadItem, b: DownloadItem) = a == b
        }
    }
}
