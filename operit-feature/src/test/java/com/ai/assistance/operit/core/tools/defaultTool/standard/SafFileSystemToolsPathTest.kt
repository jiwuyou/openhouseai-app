package com.ai.assistance.operit.core.tools.defaultTool.standard

import org.junit.Assert.assertEquals
import org.junit.Test

class SafFileSystemToolsPathTest {
    @Test
    fun nestedDocumentIdIsNotReplacedByTreeRootId() {
        assertEquals(
            "termux-home:.local/share/wuxianpi/plugins/termux-keyboard",
            selectSafDocumentId(
                treeId = "termux-home:",
                documentId = "termux-home:.local/share/wuxianpi/plugins/termux-keyboard",
            ),
        )
    }

    @Test
    fun plainTreeUriStillUsesTreeRootId() {
        assertEquals(
            "termux-home:",
            selectSafDocumentId(treeId = "termux-home:", documentId = null),
        )
    }

    @Test
    fun overwriteRequiresReadWriteTruncateMode() {
        assertEquals("rwt", selectSafOutputMode(append = false))
    }

    @Test
    fun appendKeepsWriteAppendMode() {
        assertEquals("wa", selectSafOutputMode(append = true))
    }
}
