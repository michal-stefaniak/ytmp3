package com.ytmp3

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.StateFlow

class DownloadViewModel(app: Application) : AndroidViewModel(app) {

    val downloads: StateFlow<List<DownloadItem>> = DownloadManager.downloads

    fun submitUrls(urls: List<String>, trimStart: String? = null, trimEnd: String? = null) =
        DownloadManager.submitUrls(urls, trimStart, trimEnd)

    fun retry(id: String) = DownloadManager.retry(id)

    fun clearCompleted() = DownloadManager.clearCompleted()

    fun removeItem(id: String) = DownloadManager.removeItem(id)
}
