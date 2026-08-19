package com.ytmp3

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class PackExportForegroundTest {
    @Test
    fun `pack exports declare the data sync foreground service type`() {
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            PackExportForeground.serviceType
        )
    }
}
