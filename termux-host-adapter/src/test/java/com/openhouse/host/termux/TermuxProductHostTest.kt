package com.openhouse.host.termux

import org.junit.Assert.assertEquals
import org.junit.Test

class TermuxProductHostTest {
    @Test
    fun filesEntryUsesExistingEmbeddedFileManager() {
        assertEquals(
            "com.termux.app.openhouse.files.ui.OpenHouseFilesActivity",
            TermuxProductHost.FILES_ACTIVITY_CLASS,
        )
    }

    @Test
    fun permissionsEntryUsesExistingMaintenanceCenter() {
        assertEquals(
            "com.termux.app.activities.MaintenanceCenterActivity",
            TermuxProductHost.PERMISSIONS_ACTIVITY_CLASS,
        )
    }

    @Test
    fun aiFallbackReturnsToSharedOpenHouseWorkspace() {
        assertEquals(
            "com.wuxianpi.openhouse.feature.OpenHouseActivity",
            TermuxProductHost.OPENHOUSE_ACTIVITY_CLASS,
        )
    }
}
