package com.ai.assistance.operit.rescue.plugins

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RescuePluginCatalogClientTest {
    @Test
    fun defaultHttpClientHasHardCallTimeoutBelowCoordinatorTimeout() {
        val client = createRescuePluginHttpClient()

        assertEquals(TimeUnit.SECONDS.toMillis(25), client.callTimeoutMillis.toLong())
        assertTrue(client.callTimeoutMillis < TimeUnit.SECONDS.toMillis(30))
    }

    @Test
    fun rejectsDeclaredOversizedArchiveBeforeOpeningBody() {
        val body =
            object : ResponseBody() {
                override fun contentType(): MediaType? = null

                override fun contentLength(): Long = MAX_RESCUE_PLUGIN_ARCHIVE_BYTES + 1L

                override fun source(): BufferedSource = error("Oversized body must not be opened")
            }

        val failure = runCatching { body.readBoundedPluginArchive() }.exceptionOrNull()

        assertTrue(failure is IOException)
    }

    @Test
    fun rejectsChunkedArchiveAfterReadingOneBytePastLimit() {
        val body =
            object : ResponseBody() {
                override fun contentType(): MediaType? = null

                override fun contentLength(): Long = -1L

                override fun source(): BufferedSource = Buffer().writeUtf8("12345")
            }

        val failure = runCatching { body.readBoundedPluginArchive(maxBytes = 4) }.exceptionOrNull()

        assertTrue(failure is IOException)
    }

    @Test
    fun acceptsArchiveAtExactLimit() {
        val archive = byteArrayOf(1, 2, 3, 4)

        val downloaded = archive.toResponseBody().readBoundedPluginArchive(maxBytes = 4)

        assertArrayEquals(archive, downloaded)
    }
}
