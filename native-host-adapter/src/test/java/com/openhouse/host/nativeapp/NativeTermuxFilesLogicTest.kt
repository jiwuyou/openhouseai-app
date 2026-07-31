package com.openhouse.host.nativeapp

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeTermuxFilesLogicTest {
    @Test
    fun boundedReaderAcceptsContentAtLimit() {
        val bytes = "hello".toByteArray()

        assertEquals("hello", readUtf8TextLimited(ByteArrayInputStream(bytes), bytes.size))
    }

    @Test(expected = FileTooLargeException::class)
    fun boundedReaderRejectsContentPastLimit() {
        readUtf8TextLimited(ByteArrayInputStream(ByteArray(9)), 8)
    }

    @Test
    fun knownLengthOnlyRejectsFilesPastLimit() {
        assertFalse(isKnownFileTooLarge(0, 8))
        assertFalse(isKnownFileTooLarge(8, 8))
        assertTrue(isKnownFileTooLarge(9, 8))
    }

    @Test
    fun documentNameRejectsTraversalAndSeparators() {
        assertNull(validateDocumentName("notes.txt"))
        assertTrue(validateDocumentName(" ") != null)
        assertTrue(validateDocumentName("..") != null)
        assertTrue(validateDocumentName("folder/file") != null)
        assertTrue(validateDocumentName("bad\u0000name") != null)
    }

    @Test
    fun fileSizeUsesReadableUnits() {
        assertEquals("12 B", formatFileSize(12))
        assertEquals("1.0 KiB", formatFileSize(1024))
        assertEquals("1.0 MiB", formatFileSize(1024 * 1024L))
    }
}
