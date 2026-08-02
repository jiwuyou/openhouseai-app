package com.ai.assistance.operit.rescue.plugins

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RescuePluginInstalledReaderTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun persistedIncompatibleActiveFallsBackToCompatiblePreviousAfterProcessStart() {
        val installedRoot = temporaryFolder.newFolder("installed-host-version")
        writePlugin(installedRoot, version = "2.0.0", minHostVersion = 13)
        val previousDirectory = writePlugin(installedRoot, version = "1.0.0")
        File(previousDirectory, ".bundled").writeText("")
        val state = state(active = "2.0.0", previous = "1.0.0")

        val reader = RescuePluginInstalledReader(installedRoot)

        assertNull(reader.readActive(state, PLUGIN_ID))
        val previous = reader.readPrevious(state, PLUGIN_ID)
        assertEquals("1.0.0", previous?.activeVersion)
        assertEquals("2.0.0", previous?.previousVersion)
        assertTrue(previous?.bundled == true)
    }

    @Test
    fun persistedPluginWithUnsupportedCapabilityIsNotLoadable() {
        val installedRoot = temporaryFolder.newFolder("installed-capability")
        writePlugin(
            installedRoot,
            version = "1.0.0",
            capabilities = listOf("root-shell"),
        )
        val state = state(active = "1.0.0", previous = null)

        val reader = RescuePluginInstalledReader(installedRoot)

        assertNull(reader.readActive(state, PLUGIN_ID))
        assertNull(reader.readPrevious(state, PLUGIN_ID))
    }

    @Test
    fun persistedManifestIdentityMustMatchItsInstallationDirectory() {
        val installedRoot = temporaryFolder.newFolder("installed-identity")
        writePlugin(
            installedRoot,
            version = "1.0.0",
            manifestPluginId = "wuxianpi.other",
        )
        val state = state(active = "1.0.0", previous = null)

        val reader = RescuePluginInstalledReader(installedRoot)

        assertNull(reader.readActive(state, PLUGIN_ID))
    }

    private fun state(active: String, previous: String?): JSONObject =
        JSONObject().put(
            PLUGIN_ID,
            JSONObject()
                .put("active", active)
                .put("previous", previous ?: JSONObject.NULL)
                .put("bundled", false),
        )

    private fun writePlugin(
        installedRoot: File,
        version: String,
        minHostVersion: Int = 12,
        capabilities: List<String> = listOf("termux"),
        manifestPluginId: String = PLUGIN_ID,
    ): File {
        val directory = File(File(installedRoot, PLUGIN_ID), version)
        assertTrue(directory.mkdirs())
        File(directory, "workflows").mkdirs()
        File(directory, "workflows/main.json").writeText("""{"schemaVersion":1,"steps":[]}""")
        File(directory, "manifest.json").writeText(
            JSONObject()
                .put("schemaVersion", 1)
                .put("id", manifestPluginId)
                .put("version", version)
                .put("name", "Persisted plugin")
                .put("description", "Persisted before process start")
                .put("category", "test")
                .put("entryWorkflow", "workflows/main.json")
                .put("documents", org.json.JSONArray())
                .put("requiredCapabilities", org.json.JSONArray(capabilities))
                .put("tags", org.json.JSONArray())
                .put("minHostVersion", minHostVersion)
                .toString()
        )
        return directory
    }

    private companion object {
        const val PLUGIN_ID = "wuxianpi.persisted"
    }
}
