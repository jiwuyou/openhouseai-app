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
        writePlugin(installedRoot, version = "2.0.0", minHostVersion = 15)
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

    @Test
    fun assistantContextsComeOnlyFromTheActiveInstalledVersion() {
        val installedRoot = temporaryFolder.newFolder("installed-contexts")
        writePlugin(
            installedRoot,
            version = "1.0.0",
            contextContent = "old context",
        )
        writePlugin(
            installedRoot,
            version = "2.0.0",
            contextContent = "active context",
        )
        val reader = RescuePluginInstalledReader(installedRoot)

        val contexts =
            reader.readActiveAssistantContexts(state(active = "2.0.0", previous = "1.0.0"))

        assertEquals(listOf("active context"), contexts.map { it.content })
        assertEquals(listOf("prompts/instruction.md"), contexts.map { it.path })
        assertEquals(listOf("session"), contexts.map { it.context.scope })
        assertTrue(contexts.single().pluginRoot.endsWith("/$PLUGIN_ID/2.0.0"))
    }

    @Test
    fun oversizedStaticAssistantContextIsSkippedWithoutBreakingInstalledPlugin() {
        val installedRoot = temporaryFolder.newFolder("installed-large-context")
        writePlugin(
            installedRoot,
            version = "1.0.0",
            contextContent = "x".repeat(4097),
        )
        val reader = RescuePluginInstalledReader(installedRoot)
        val state = state(active = "1.0.0", previous = null)

        assertTrue(reader.readActiveAssistantContexts(state).isEmpty())
        assertEquals("1.0.0", reader.readActive(state, PLUGIN_ID)?.activeVersion)
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
        contextContent: String? = null,
    ): File {
        val directory = File(File(installedRoot, PLUGIN_ID), version)
        assertTrue(directory.mkdirs())
        File(directory, "workflows").mkdirs()
        File(directory, "workflows/main.json").writeText("""{"schemaVersion":1,"steps":[]}""")
        contextContent?.let {
            File(directory, "prompts").mkdirs()
            File(directory, "prompts/instruction.md").writeText(it)
        }
        File(directory, "manifest.json").writeText(
            JSONObject()
                .put("schemaVersion", 1)
                .put("id", manifestPluginId)
                .put("version", version)
                .put("name", "Persisted plugin")
                .put("description", "Persisted before process start")
                .put("category", "test")
                .put("entryWorkflow", "workflows/main.json")
                .put(
                    "assistantContexts",
                    org.json.JSONArray(
                        if (contextContent == null) emptyList()
                        else listOf(
                            JSONObject()
                                .put("path", "prompts/instruction.md")
                                .put("scope", "session")
                        )
                    ),
                )
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
