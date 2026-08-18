package com.ytmp3

import android.content.Intent
import android.net.Uri
import android.os.Build

object ImportUris {
    fun fromIntent(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(intent.parcelableExtraCompat(Intent.EXTRA_STREAM))
        Intent.ACTION_SEND_MULTIPLE -> intent.parcelableArrayListExtraCompat(Intent.EXTRA_STREAM).orEmpty()
        else -> emptyList()
    }
}

private fun Intent.parcelableExtraCompat(key: String): Uri? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(key, Uri::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(key) as? Uri
    }

private fun Intent.parcelableArrayListExtraCompat(key: String): ArrayList<Uri>? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableArrayListExtra(key, Uri::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableArrayListExtra(key)
    }
