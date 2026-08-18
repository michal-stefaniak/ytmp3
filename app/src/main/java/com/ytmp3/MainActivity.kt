package com.ytmp3

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.ytmp3.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val vm: DownloadViewModel by viewModels()
    private val adapter = DownloadAdapter(
        onRetry      = { id  -> vm.retry(id) },
        onErrorClick = { msg -> showErrorDialog(msg) },
        onPlayClick  = { fp  -> playFile(fp) },
        onLongClick  = { url -> copyUrlToClipboard(url) },
        onEditClick  = { item -> item.filePath?.let { openSampleEditor(it, item.title, item.id) } }
    )
    private var lastSniffedUrl: String? = null
    // Guards against stacking a second auto-opened SampleEditorActivity before the first has
    // actually taken MainActivity out of the foreground (see the auto-open collector below).
    // Cleared on onResume() -- reached once the user returns from a previously opened editor.
    private var editorOpenInFlight = false

    private val importAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        createProjectsAndOpenFirst(listOf(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.rvDownloads.layoutManager = LinearLayoutManager(this)
        b.rvDownloads.adapter = adapter

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false
            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {
                val pos = vh.adapterPosition
                if (pos != RecyclerView.NO_ID.toInt() && pos < adapter.currentList.size) {
                    vm.removeItem(adapter.currentList[pos].id)
                }
            }
        }).attachToRecyclerView(b.rvDownloads)

        b.cbTrim.setOnCheckedChangeListener { _, checked ->
            b.llTrimFields.visibility = if (checked) View.VISIBLE else View.GONE
        }

        b.cbSampleMode.setOnCheckedChangeListener { _, checked ->
            Prefs.sampleMode = checked
            if (checked) {
                b.cbTrim.isChecked = false
                b.cbTrim.isEnabled = false
                b.llTrimFields.visibility = View.GONE
            } else {
                b.cbTrim.isEnabled = true
            }
        }
        b.cbSampleMode.isChecked = Prefs.sampleMode

        b.btnDownload.setOnClickListener {
            val raw = b.etUrls.text.toString()
            val urls = raw.lines().map { it.trim() }.filter { it.startsWith("http") }
            if (urls.isEmpty()) {
                showSimpleDialog("Nothing to download", "Paste at least one YouTube URL, one per line.")
                return@setOnClickListener
            }
            val playlists = urls.filter { isPlaylistUrl(it) }
            val singles = urls.filterNot { isPlaylistUrl(it) }
            if (singles.isNotEmpty()) submitWithDupeCheck(singles)
            playlists.forEach { openPlaylistPreview(it) }
            b.etUrls.text.clear()
        }

        b.btnClear.setOnClickListener { vm.clearCompleted() }
        b.btnImportAudio.setOnClickListener { importAudio.launch(arrayOf("audio/*")) }
        b.btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        b.btnHistory.setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }
        b.btnLibrary.setOnClickListener { startActivity(Intent(this, LibraryActivity::class.java)) }

        lifecycleScope.launch {
            // repeatOnLifecycle(STARTED) stops this collector while MainActivity is
            // stopped/backgrounded -- without it, a sample-mode download finishing while the app
            // is backgrounded (or while SampleEditorActivity, opened by an earlier auto-open, is
            // covering MainActivity and has stopped it) would call startActivity() from a
            // non-foreground context, which Android 10+ can silently block.
            //
            // editorOpenInFlight guards a narrower race repeatOnLifecycle alone can't close: two
            // sample-mode downloads finishing within the *same* StateFlow emission while
            // MainActivity is genuinely in the foreground (no editor open yet). Without it,
            // openSampleEditor() for the first item wouldn't actually stop MainActivity until the
            // activity transition completes, so this synchronous collect block would still reach
            // the second item and stack a duplicate editor on top of the first. Only the single
            // next not-yet-opened item is opened per emission; onResume() (reached once the user
            // returns from a previously opened editor) clears the flag so the next one can open.
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.downloads.collect { items ->
                    adapter.submitList(items.toList())
                    if (editorOpenInFlight) return@collect
                    val toOpen = items.firstOrNull { item ->
                        item.sampleMode && item.status == DownloadStatus.DONE &&
                            item.filePath != null &&
                            !vm.hasAutoOpened(item.id)
                    }
                    if (toOpen?.filePath != null && vm.markAutoOpened(toOpen.id)) {
                        editorOpenInFlight = true
                        openSampleEditor(toOpen.filePath, toOpen.title, toOpen.id)
                    }
                }
            }
        }

        handleShareIntent(intent)
        requestNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        editorOpenInFlight = false
        if (Prefs.clipboardSniff) checkClipboard()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        intent ?: return
        val uris = ImportUris.fromIntent(intent)
        if (uris.isNotEmpty()) createProjectsAndOpenFirst(uris, intent.flags)
    }

    private fun createProjectsAndOpenFirst(uris: List<Uri>, grantFlags: Int) {
        val projects = uris.map { uri ->
            persistReadGrant(uri, grantFlags)
            SampleProject(
                id = UUID.randomUUID().toString(),
                sourceUri = uri.toString(),
                title = DocumentFile.fromSingleUri(this, uri)?.name ?: "Imported audio"
            )
        }
        projects.forEach(ProjectDb.get(this)::upsertProject)
        openProject(projects.first().id)
    }

    private fun persistReadGrant(uri: Uri, grantFlags: Int) {
        val readGrant = grantFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (readGrant == 0) return
        try {
            contentResolver.takePersistableUriPermission(uri, readGrant)
        } catch (_: SecurityException) {
            // Shares are valid for this activity session even when their provider offers no
            // persistable grant; the project remains usable until Android revokes that grant.
        }
    }

    fun openProject(projectId: String) {
        startActivity(
            Intent(this, SampleEditorActivity::class.java)
                .putExtra(SampleEditorActivity.EXTRA_PROJECT_ID, projectId)
        )
    }

    private fun checkClipboard() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: return
        val url = Regex("""https?://(?:www\.)?(?:youtube\.com|youtu\.be)[^\s]*""").find(text)?.value ?: return
        if (url == lastSniffedUrl || vm.downloads.value.any { it.url == url }) return
        lastSniffedUrl = url
        Snackbar.make(b.root, "YouTube URL found in clipboard", Snackbar.LENGTH_LONG)
            .setAction("Download") { submitWithDupeCheck(listOf(url)) }
            .show()
    }

    private fun submitWithDupeCheck(urls: List<String>) {
        if (!Prefs.dupeCheck) { doSubmit(urls); return }
        val dupes = urls.filter { HistoryDb.get(this).findByUrl(it) != null }
        val fresh = urls - dupes.toSet()
        if (fresh.isNotEmpty()) doSubmit(fresh)
        if (dupes.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Already downloaded")
                .setMessage("${dupes.size} URL(s) found in history.\nDownload again?")
                .setPositiveButton("Yes") { _, _ -> doSubmit(dupes) }
                .setNegativeButton("No", null)
                .show()
        }
    }

    private fun doSubmit(urls: List<String>) {
        val start = b.etTrimStart.text.toString().takeIf { b.cbTrim.isChecked && it.isNotBlank() }
        val end = b.etTrimEnd.text.toString().takeIf { b.cbTrim.isChecked && it.isNotBlank() }
        // Auto-open is a single-URL feature: "Sample mode on" plus a multi-line paste of several
        // URLs would otherwise auto-chain an editor open per finished download, exactly the
        // auto-chaining the design forbids for playlists -- just reached through a different entry
        // point. Sample mode still fetches full tracks either way (no --download-sections is added
        // regardless, since the trim fields are hidden whenever sample mode is on); only the
        // auto-open trigger is restricted to a genuine single-URL submission.
        val autoOpenSampleMode = Prefs.sampleMode && urls.size == 1
        vm.submitUrls(urls, start, end, sampleMode = autoOpenSampleMode)
    }

    private fun openSampleEditor(filePath: String, title: String, historyId: String?) {
        startActivity(
            Intent(this, SampleEditorActivity::class.java)
                .putExtra("filePath", filePath)
                .putExtra("title", title)
                .putExtra("historyId", historyId)
        )
    }

    private fun playFile(filePath: String) {
        val uri = if (filePath.startsWith("content://")) {
            Uri.parse(filePath)
        } else {
            FileProvider.getUriForFile(this, "com.ytmp3.fileprovider", File(filePath))
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "audio/mpeg")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "No music player found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyUrlToClipboard(url: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("url", url))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, "URL copied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showErrorDialog(error: String) {
        AlertDialog.Builder(this)
            .setTitle("Download failed")
            .setMessage(error)
            .setPositiveButton("OK", null)
            .setNegativeButton("Copy error") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("error", error))
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun showSimpleDialog(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun openPlaylistPreview(url: String) {
        startActivity(Intent(this, PlaylistPreviewActivity::class.java).putExtra("url", url))
    }

    private fun isPlaylistUrl(url: String) = url.contains("list=") || url.contains("/playlist")

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }
}
