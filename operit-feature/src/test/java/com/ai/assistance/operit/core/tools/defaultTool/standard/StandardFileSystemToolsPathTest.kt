package com.ai.assistance.operit.core.tools.defaultTool.standard

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class StandardFileSystemToolsPathTest {
    @Test
    fun applyFileAcceptsPathsRelativeToSafBookmarkRoot() {
        assertNull(
            validateApplyFilePath(
                ".termux/termux.properties",
                "apply_file",
                "repo:termux-home",
            ),
        )
    }

    @Test
    fun applyFileStillRequiresAbsoluteAndroidPaths() {
        assertNotNull(
            validateApplyFilePath(
                ".termux/termux.properties",
                "apply_file",
                "android",
            ),
        )
    }
}
