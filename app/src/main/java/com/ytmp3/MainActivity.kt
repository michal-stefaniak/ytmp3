package com.ytmp3

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.ytmp3.databinding.ActivityMainBinding
import java.util.UUID

/** The Play-product entry point: local documents and Android Shares only. */
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    private val importAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        createProjectsAndOpenFirst(listOf(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnImportAudio.setOnClickListener { importAudio.launch(arrayOf("audio/*")) }
        binding.btnImportAudioMain.setOnClickListener { importAudio.launch(arrayOf("audio/*")) }
        binding.btnLibrary.setOnClickListener { startActivity(Intent(this, LibraryActivity::class.java)) }
        binding.btnOpenLibrary.setOnClickListener { startActivity(Intent(this, LibraryActivity::class.java)) }

        handleShareIntent(intent)
        requestNotificationPermission()
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
            // Shares remain available during this activity session when their source does not
            // grant persistable access.
        }
    }

    private fun openProject(projectId: String) {
        startActivity(Intent(this, SampleEditorActivity::class.java).putExtra(SampleEditorActivity.EXTRA_PROJECT_ID, projectId))
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }
}
