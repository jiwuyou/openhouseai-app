package com.wuxianpi.openhouse.feature.desktop

import com.wuxianpi.openhouse.core.registry.OpenHouseBuiltins
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopCatalogTest {
    @Test
    fun fixedEntriesAlwaysExistAndCannotBeOverridden() {
        val dynamic = OpenHouseBuiltins.components().first { it.id == DesktopCatalog.ID_BASIC }
        val merged = DesktopCatalog.merge(listOf(dynamic))
        val ids = merged.map { it.id }

        assertEquals(OpenHouseBuiltins.components().size, merged.size)
        assertTrue(DesktopCatalog.fixed().all { it.id in ids })
        assertEquals("基础模式", merged.first { it.id == DesktopCatalog.ID_BASIC }.title)
    }
}
