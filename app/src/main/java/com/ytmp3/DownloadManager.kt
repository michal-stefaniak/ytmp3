package com.ytmp3

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.StatFs
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

object DownloadManager {
    private lateinit var appCtx: Context
    private val _downloads = MutableStateFlow<List<DownloadItem>>(emptyList())
    val downloads: StateFlow<List<DownloadItem>> = _downloads
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var semaphore = Semaphore(2)

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        semaphore = Semaphore(Prefs.concurrency)
    }

    fun submitUrls(urls: List<String>, trimStart: String? = null, trimEnd: String? = null) {
        val items = urls.map { DownloadItem(url = it) }
        _downloads.value = _downloads.value + items
        startService()
        items.forEach { startDownload(it, trimStart, trimEnd) }
    }

    fun retry(id: String) {
        val existing = _downloads.value.find { it.id == id } ?: return
        val fresh = existing.copy(status = DownloadStatus.QUEUED, progress = 0, errorMsg = null, filePath = null)
        _downloads.value = _downloads.value.map { if (it.id == id) fresh else it }
        startService()
        startDownload(fresh)
    }

    fun clearCompleted() {
        _downloads.value = _downloads.value.filter {
            it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING
        }
    }

    fun removeItem(id: String) {
        _downloads.value = _downloads.value.filter { it.id != id }
    }

    fun isActive() = _downloads.value.any {
        it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING
    }

    fun updateConcurrency(n: Int) {
        semaphore = Semaphore(n.coerceIn(1, 5))
    }

    suspend fun fetchPlaylistEntries(url: String): List<PlaylistEntry> = withContext(Dispatchers.IO) {
        val request = YoutubeDLRequest(url).apply {
            addOption("--flat-playlist")
            addOption("-J")
            addOption("--no-warnings")
        }
        try {
            val response = YoutubeDL.getInstance().execute(request)
            val json = JSONObject(response.out)
            val entries = json.optJSONArray("entries") ?: return@withContext emptyList()
            (0 until entries.length()).mapNotNull { i ->
                val e = entries.getJSONObject(i)
                val vid = e.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                PlaylistEntry(
                    id = vid,
                    title = e.optString("title", vid),
                    url = e.optString("url").let { raw ->
                        if (raw.startsWith("http")) raw
                        else "https://www.youtube.com/watch?v=$vid"
                    }
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun startService() {
        ContextCompat.startForegroundService(
            appCtx, Intent(appCtx, DownloadForegroundService::class.java)
        )
    }

    private fun startDownload(item: DownloadItem, trimStart: String? = null, trimEnd: String? = null) {
        scope.launch {
            if (Prefs.wifiOnly) {
                while (!isOnWifi()) { delay(15_000) }
            }

            semaphore.withPermit {
                if (Prefs.storageWarn) {
                    val free = StatFs(appCtx.cacheDir.absolutePath).availableBytes
                    if (free < 200L * 1024 * 1024) {
                        update(item.id) { it.copy(status = DownloadStatus.ERROR, errorMsg = "Low storage (<200MB free)") }
                        saveHistory(item.id, item.url, item.title, "ERROR")
                        return@withPermit
                    }
                }

                update(item.id) { it.copy(status = DownloadStatus.DOWNLOADING) }
                val tempDir = File(appCtx.cacheDir, "dl_${item.id}").also { it.mkdirs() }
                try {
                    val bitrate = Prefs.bitrate
                    val outputTemplate = if (Prefs.autoFolder)
                        "${tempDir.absolutePath}/%(uploader)s/%(title)s.%(ext)s"
                    else
                        "${tempDir.absolutePath}/%(title)s.%(ext)s"

                    val request = YoutubeDLRequest(item.url).apply {
                        addOption("--extract-audio")
                        addOption("--audio-format", "mp3")
                        addOption("--audio-quality", "${bitrate}k")
                        addOption("--embed-thumbnail")
                        addOption("--embed-metadata")
                        addOption("--convert-thumbnails", "jpg")
                        addOption("--no-playlist")
                        addOption("--no-warnings")
                        addOption("-o", outputTemplate)
                        if (Prefs.sponsorBlock) addOption("--sponsorblock-remove", "sponsor,selfpromo,intro,outro,interaction")
                        if (Prefs.normalise) addOption("--postprocessor-args", "ffmpeg:-af loudnorm")
                        if (trimStart != null) addOption("--download-sections", "*${trimStart}-${trimEnd ?: "inf"}")
                    }

                    var resolvedTitle = item.url
                    YoutubeDL.getInstance().execute(request) { progress, etaSecs, line ->
                        val pct = progress.toInt().coerceIn(0, 100)
                        val kbps = Regex("""([\d.]+)(KiB|MiB)/s""").find(line)?.let { m ->
                            val v = m.groupValues[1].toFloatOrNull() ?: 0f
                            if (m.groupValues[2] == "MiB") (v * 1024).toInt() else v.toInt()
                        } ?: 0
                        if (line.isNotBlank() && resolvedTitle == item.url) resolvedTitle = line.trim()
                        update(item.id) { it.copy(progress = pct, title = resolvedTitle, speedKbps = kbps, etaSeconds = etaSecs) }
                    }

                    if (Prefs.cleanTitle) {
                        resolvedTitle = resolvedTitle
                            .replace(Regex("""\s*[\(\[]\s*(?:Official\s+(?:Video|Audio|Music\s+Video|Lyric\s+Video)|Lyrics?|4K|HD|HQ|MV|Audio|Video)\s*[\)\]]""", RegexOption.IGNORE_CASE), "")
                            .replace(Regex("""\s*\|\s*(?:Lyrics?|Official|Audio|Video|HD|HQ)\s*$""", RegexOption.IGNORE_CASE), "")
                            .trim()
                    }

                    val mp3 = tempDir.walkTopDown().firstOrNull { it.extension == "mp3" }
                    val dirUriStr = Prefs.downloadDirUri
                    val filePath: String? = mp3?.let {
                        if (dirUriStr != null) {
                            copyToSaf(it, Uri.parse(dirUriStr))?.toString()
                        } else {
                            val destDir = File(appCtx.getExternalFilesDir(null), "Music").also { d -> d.mkdirs() }
                            it.copyTo(File(destDir, it.name), overwrite = true).absolutePath
                        }
                    }
                    tempDir.deleteRecursively()

                    update(item.id) { it.copy(status = DownloadStatus.DONE, progress = 100, title = resolvedTitle, filePath = filePath) }
                    saveHistory(item.id, item.url, resolvedTitle, "DONE")
                } catch (e: Exception) {
                    tempDir.deleteRecursively()
                    update(item.id) { it.copy(status = DownloadStatus.ERROR, errorMsg = e.message ?: "Failed") }
                    saveHistory(item.id, item.url, _downloads.value.find { it.id == item.id }?.title ?: item.url, "ERROR")
                }
            }
        }
    }

    private fun copyToSaf(file: File, treeUri: Uri): Uri? {
        val tree = DocumentFile.fromTreeUri(appCtx, treeUri) ?: return null
        val dest = tree.createFile("audio/mpeg", file.nameWithoutExtension) ?: return null
        appCtx.contentResolver.openOutputStream(dest.uri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        }
        return dest.uri
    }

    private fun isOnWifi(): Boolean {
        val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        return cm.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    private fun saveHistory(id: String, url: String, title: String, status: String) {
        HistoryDb.get(appCtx).insert(HistoryRecord(id, url, title, System.currentTimeMillis(), status))
    }

    private fun update(id: String, f: (DownloadItem) -> DownloadItem) {
        _downloads.value = _downloads.value.map { if (it.id == id) f(it) else it }
    }
}
