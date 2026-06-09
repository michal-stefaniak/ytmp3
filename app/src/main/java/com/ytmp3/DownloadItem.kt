package com.ytmp3

import java.util.UUID

enum class DownloadStatus { QUEUED, DOWNLOADING, DONE, ERROR }

data class DownloadItem(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val title: String = url,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val progress: Int = 0,
    val errorMsg: String? = null,
    val filePath: String? = null,
    val speedKbps: Int = 0,
    val etaSeconds: Long = 0
)
