package com.ytmp3

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ytmp3.databinding.ActivityPlaylistPreviewBinding
import com.ytmp3.databinding.ItemPlaylistBinding
import kotlinx.coroutines.launch

private val PLAYLIST_DIFF = object : DiffUtil.ItemCallback<PlaylistEntry>() {
    override fun areItemsTheSame(a: PlaylistEntry, b: PlaylistEntry) = a.id == b.id
    override fun areContentsTheSame(a: PlaylistEntry, b: PlaylistEntry) = a == b
}

class PlaylistPreviewActivity : AppCompatActivity() {

    private lateinit var b: ActivityPlaylistPreviewBinding
    private val entries = mutableListOf<PlaylistEntry>()
    private lateinit var adapter: PlaylistAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPlaylistPreviewBinding.inflate(layoutInflater)
        setContentView(b.root)
        supportActionBar?.title = "Playlist"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        adapter = PlaylistAdapter()
        b.rvPlaylist.adapter = adapter

        b.btnSelectAll.setOnClickListener {
            entries.forEach { it.selected = true }
            adapter.notifyDataSetChanged()
        }
        b.btnSelectNone.setOnClickListener {
            entries.forEach { it.selected = false }
            adapter.notifyDataSetChanged()
        }
        b.btnDownload.setOnClickListener {
            val selected = entries.filter { it.selected }.map { it.url }
            if (selected.isEmpty()) {
                Toast.makeText(this, "Select at least one track", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            DownloadManager.submitUrls(selected)
            Toast.makeText(this, "Queued ${selected.size} tracks", Toast.LENGTH_SHORT).show()
            finish()
        }

        val url = intent.getStringExtra("url") ?: run { finish(); return }
        loadPlaylist(url)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun loadPlaylist(url: String) {
        b.progressPlaylist.visibility = View.VISIBLE
        b.tvPlaylistStatus.text = "Fetching playlist..."
        b.btnDownload.isEnabled = false
        lifecycleScope.launch {
            val result = DownloadManager.fetchPlaylistEntries(url)
            b.progressPlaylist.visibility = View.GONE
            if (result.isEmpty()) {
                b.tvPlaylistStatus.text = "No tracks found"
                AlertDialog.Builder(this@PlaylistPreviewActivity)
                    .setTitle("Couldn't load playlist")
                    .setMessage("No tracks were found for this URL. Check that it's a valid YouTube playlist.")
                    .setPositiveButton("OK") { _, _ -> finish() }
                    .setCancelable(false)
                    .show()
            } else {
                b.tvPlaylistStatus.text = "${result.size} tracks"
                entries.clear()
                entries.addAll(result)
                adapter.submitList(entries.toList())
                b.btnDownload.isEnabled = true
            }
        }
    }

    inner class PlaylistAdapter : ListAdapter<PlaylistEntry, PlaylistAdapter.VH>(PLAYLIST_DIFF) {
        inner class VH(private val b: ItemPlaylistBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(item: PlaylistEntry) {
                b.tvTrackTitle.text = item.title
                b.cbTrack.isChecked = item.selected
                b.cbTrack.setOnCheckedChangeListener(null)
                b.root.setOnClickListener {
                    item.selected = !item.selected
                    b.cbTrack.isChecked = item.selected
                }
                b.cbTrack.setOnCheckedChangeListener { _, checked -> item.selected = checked }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            ItemPlaylistBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    }
}
