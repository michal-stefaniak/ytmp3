package com.ytmp3

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.DocumentsContract

/**
 * Free-space checks for wherever output actually lands: [Prefs.downloadDirUri]'s SAF tree when
 * set (which can be a different volume entirely, e.g. an SD card or a cloud-backed provider),
 * or [fallbackDir] otherwise -- never cacheDir (only ever used as scratch space). Checking
 * cacheDir unconditionally could pass with ample internal space while the real destination is
 * full, or vice versa. Returns null if the destination's free space can't be determined.
 *
 * Callers on Dispatchers.Main must call this from a coroutine and switch to Dispatchers.IO first
 * -- the SAF path performs a blocking ContentResolver IPC query to another process.
 */
object StorageUtil {

    fun availableBytes(context: Context, dirUriStr: String?, fallbackDir: java.io.File): Long? =
        if (dirUriStr != null) {
            availableBytesForSafTree(context, Uri.parse(dirUriStr))
        } else {
            StatFs(fallbackDir.absolutePath).availableBytes
        }

    private fun availableBytesForSafTree(context: Context, treeUri: Uri): Long? = try {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri).substringBefore(':')
        val authority = treeUri.authority
        if (authority == null) {
            null
        } else {
            context.contentResolver.query(
                DocumentsContract.buildRootsUri(authority),
                arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_AVAILABLE_BYTES),
                null, null, null
            )?.use { c ->
                val rootIdIdx = c.getColumnIndex(DocumentsContract.Root.COLUMN_ROOT_ID)
                val availIdx = c.getColumnIndex(DocumentsContract.Root.COLUMN_AVAILABLE_BYTES)
                var result: Long? = null
                while (result == null && c.moveToNext()) {
                    if (rootIdIdx >= 0 && availIdx >= 0 && c.getString(rootIdIdx) == rootId && !c.isNull(availIdx)) {
                        result = c.getLong(availIdx)
                    }
                }
                result
            }
        }
    } catch (e: Exception) {
        null
    }
}
