package com.ytmp3

import android.content.pm.ServiceInfo

/** Keeps the worker's runtime type aligned with its manifest declaration. */
internal object PackExportForeground {
    const val serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
}
