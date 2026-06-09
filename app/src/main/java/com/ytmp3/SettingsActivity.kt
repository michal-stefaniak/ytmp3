package com.ytmp3

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.switchmaterial.SwitchMaterial
import com.yausername.youtubedl_android.YoutubeDL
import com.ytmp3.databinding.ActivitySettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding

    private val pickDir = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            Prefs.downloadDirUri = uri.toString()
            updateDirLabel()
        }
    }

    private val exportQueue = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            val urls = DownloadManager.downloads.value
                .filter { it.status == DownloadStatus.QUEUED }
                .joinToString("\n") { it.url }
            contentResolver.openOutputStream(uri)?.use { it.write(urls.toByteArray()) }
            Toast.makeText(this, "Queue exported", Toast.LENGTH_SHORT).show()
        }
    }

    private val importQueue = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: return@registerForActivityResult
            val urls = text.lines().map { it.trim() }.filter { it.startsWith("http") }
            if (urls.isEmpty()) {
                Toast.makeText(this, "No URLs found in file", Toast.LENGTH_SHORT).show()
            } else {
                DownloadManager.submitUrls(urls)
                Toast.makeText(this, "Imported ${urls.size} URLs", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        supportActionBar?.title = "Settings"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        setupBitratePicker()
        setupConcurrencyPicker()
        setupDirPicker()
        setupToggles()
        setupQueueButtons()
        setupUpdateButton()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun setupDirPicker() {
        updateDirLabel()
        b.btnPickDir.setOnClickListener { pickDir.launch(null) }
    }

    private fun updateDirLabel() {
        val uriStr = Prefs.downloadDirUri
        b.tvDirPath.text = if (uriStr != null)
            Uri.parse(uriStr).lastPathSegment?.replace("primary:", "") ?: uriStr
        else "Default: app storage Music folder"
    }

    private fun setupToggles() {
        fun sw(switch: SwitchMaterial, get: () -> Boolean, set: (Boolean) -> Unit) {
            switch.isChecked = get()
            switch.setOnCheckedChangeListener { _, v -> set(v) }
        }
        sw(b.swWifiOnly,       { Prefs.wifiOnly },       { Prefs.wifiOnly = it })
        sw(b.swStorageWarn,    { Prefs.storageWarn },    { Prefs.storageWarn = it })
        sw(b.swClipboardSniff, { Prefs.clipboardSniff }, { Prefs.clipboardSniff = it })
        sw(b.swDupeCheck,      { Prefs.dupeCheck },      { Prefs.dupeCheck = it })
        sw(b.swCleanTitle,     { Prefs.cleanTitle },     { Prefs.cleanTitle = it })
        sw(b.swNormalise,      { Prefs.normalise },      { Prefs.normalise = it })
        sw(b.swSponsorBlock,   { Prefs.sponsorBlock },   { Prefs.sponsorBlock = it })
        sw(b.swAutoFolder,     { Prefs.autoFolder },     { Prefs.autoFolder = it })
        sw(b.swShowSpeed,      { Prefs.showSpeed },      { Prefs.showSpeed = it })
    }

    private fun setupQueueButtons() {
        b.btnExportQueue.setOnClickListener { exportQueue.launch("ytmp3-queue.txt") }
        b.btnImportQueue.setOnClickListener { importQueue.launch(arrayOf("text/plain", "text/*")) }
    }

    private fun setupBitratePicker() {
        val options = listOf("128 kbps", "192 kbps", "320 kbps")
        val values = listOf(128, 192, 320)
        b.spinnerBitrate.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
        b.spinnerBitrate.setSelection(values.indexOf(Prefs.bitrate).takeIf { it >= 0 } ?: 2)
        b.spinnerBitrate.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { Prefs.bitrate = values[pos] }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun setupConcurrencyPicker() {
        val options = listOf("1 at a time", "2 at a time", "3 at a time")
        val values = listOf(1, 2, 3)
        b.spinnerConcurrency.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
        b.spinnerConcurrency.setSelection(values.indexOf(Prefs.concurrency).takeIf { it >= 0 } ?: 1)
        b.spinnerConcurrency.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                Prefs.concurrency = values[pos]
                DownloadManager.updateConcurrency(values[pos])
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun setupUpdateButton() {
        b.btnUpdate.setOnClickListener {
            b.btnUpdate.isEnabled = false
            b.tvUpdateStatus.text = "Checking for updates..."
            b.tvUpdateStatus.visibility = View.VISIBLE
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { YoutubeDL.getInstance().updateYoutubeDL(this@SettingsActivity) }
                }
                b.btnUpdate.isEnabled = true
                result.fold(
                    onSuccess = { status ->
                        b.tvUpdateStatus.text = when (status) {
                            YoutubeDL.UpdateStatus.DONE -> "Updated successfully"
                            YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "Already up to date"
                            else -> "Update failed"
                        }
                        Toast.makeText(this@SettingsActivity, b.tvUpdateStatus.text, Toast.LENGTH_SHORT).show()
                    },
                    onFailure = {
                        b.tvUpdateStatus.text = "Update failed: ${it.message}"
                        Toast.makeText(this@SettingsActivity, "Update failed", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }
}
