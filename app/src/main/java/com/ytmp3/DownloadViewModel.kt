package com.ytmp3

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.StateFlow

class DownloadViewModel(app: Application) : AndroidViewModel(app) {

    val downloads: StateFlow<List<DownloadItem>> = DownloadManager.downloads

    fun submitUrls(urls: List<String>, trimStart: String? = null, trimEnd: String? = null, sampleMode: Boolean = false) =
        DownloadManager.submitUrls(urls, trimStart, trimEnd, sampleMode)

    // Tracks which finished sample-mode items have already auto-opened their editor. Lives here
    // rather than as an Activity-local var: MainActivity has no configChanges, so a rotation
    // destroys and recreates it -- an Activity-scoped set would be wiped, and DownloadManager's
    // StateFlow would immediately re-deliver the already-finished item to a fresh collector,
    // re-opening the editor. The ViewModel survives configuration changes, so this doesn't.
    private val autoOpenedIds = mutableSetOf<String>()
    fun markAutoOpened(id: String): Boolean = autoOpenedIds.add(id) // true if newly added
    fun hasAutoOpened(id: String): Boolean = id in autoOpenedIds

    fun retry(id: String) = DownloadManager.retry(id)

    fun clearCompleted() = DownloadManager.clearCompleted()

    fun removeItem(id: String) = DownloadManager.removeItem(id)
}
