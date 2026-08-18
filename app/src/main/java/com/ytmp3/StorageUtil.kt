package com.ytmp3

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Free-space checks for wherever output actually lands: [Prefs.downloadDirUri]'s SAF tree when
 * set (which can be a different volume entirely, e.g. an SD card or a cloud-backed provider),
 * or [fallbackDir] otherwise -- never cacheDir (only ever used as scratch space). Checking
 * cacheDir unconditionally could pass with ample internal space while the real destination is
 * full, or vice versa. Returns null if the destination's free space can't be determined, or if
 * the provider doesn't answer within the timeout -- callers should treat null as "skip the
 * precheck," not as zero free space.
 *
 * Safe to call from any dispatcher, including Main: internally dispatches to IO and bounds the
 * SAF path's blocking ContentResolver IPC query with a timeout, so a hung/slow provider can't
 * block the caller indefinitely.
 */
object StorageUtil {

    suspend fun availableBytes(context: Context, dirUriStr: String?, fallbackDir: File): Long? =
        withTimeoutOrNull(5_000) {
            withContext(Dispatchers.IO) {
                if (dirUriStr != null) {
                    availableBytesForSafTree(context, Uri.parse(dirUriStr))
                } else {
                    StatFs(fallbackDir.absolutePath).availableBytes
                }
            }
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
