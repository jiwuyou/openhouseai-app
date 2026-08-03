package com.ai.assistance.operit.rescue.plugins

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RescuePluginArchiveInstallerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun validatesDigestManifestAndReferencedFiles() {
        val archive =
            zip(
                "manifest.json" to
                    """{"schemaVersion":1,"id":"wuxianpi.test","version":"1.0.0","name":"Test","description":"Test plugin","category":"test","minHostVersion":1,"requiredCapabilities":[],"tags":[],"entryWorkflow":"workflows/main.json","documents":[{"path":"docs/readme.md","title":"Readme"}]}""",
                "workflows/main.json" to """{"schemaVersion":1,"steps":[{"id":"one","kind":"tool"}]}""",
                "docs/readme.md" to "hello",
            )
        val installer = RescuePluginArchiveInstaller(temporaryFolder.newFolder("staging"))

        val (directory, manifest) =
            installer.extractAndValidate(
                archive = archive,
                expectedSha256 = RescuePluginArchiveInstaller.sha256(archive),
                expectedPluginId = "wuxianpi.test",
                expectedVersion = "1.0.0",
            )

        assertEquals("wuxianpi.test", manifest.id)
        assertTrue(File(directory, "docs/readme.md").isFile)
    }

    @Test
    fun validatesAssistantContextFilesAndRejectsMissingDeclarationTarget() {
        val validArchive =
            zip(
                "manifest.json" to
                    """{"schemaVersion":1,"id":"wuxianpi.guide","version":"1.0.0","name":"Guide","description":"Guide plugin","category":"knowledge","minHostVersion":13,"requiredCapabilities":[],"tags":[],"assistantContexts":[{"path":"prompts/instruction.md","scope":"session"},{"path":"scripts/time.js","scope":"turn","provider":"javascript","function":"buildContext"}],"documents":[]}""",
                "prompts/instruction.md" to "Read the guide first.",
                "scripts/time.js" to "function buildContext() { return new Date().toISOString(); }",
            )
        val installer = RescuePluginArchiveInstaller(temporaryFolder.newFolder("contexts"))

        val (_, manifest) =
            installer.extractAndValidate(
                validArchive,
                RescuePluginArchiveInstaller.sha256(validArchive),
                "wuxianpi.guide",
                "1.0.0",
            )
        assertEquals(listOf("session", "turn"), manifest.assistantContexts.map { it.scope })

        val missingArchive =
            zip(
                "manifest.json" to
                    """{"schemaVersion":1,"id":"wuxianpi.guide","version":"1.0.1","name":"Guide","description":"Guide plugin","category":"knowledge","minHostVersion":13,"requiredCapabilities":[],"tags":[],"assistantContexts":[{"path":"prompts/instruction.md","scope":"session"}],"documents":[]}""",
            )
        val failure =
            runCatching {
                installer.extractAndValidate(
                    missingArchive,
                    RescuePluginArchiveInstaller.sha256(missingArchive),
                    "wuxianpi.guide",
                    "1.0.1",
                )
            }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun rejectsZipTraversalBeforeWritingOutsideStaging() {
        val root = temporaryFolder.newFolder("traversal")
        val archive = zip("../escaped.txt" to "bad")
        val installer = RescuePluginArchiveInstaller(File(root, "staging"))

        val failure =
            runCatching {
                installer.extractAndValidate(
                    archive,
                    RescuePluginArchiveInstaller.sha256(archive),
                    "wuxianpi.test",
                    "1.0.0",
                )
            }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertFalse(File(root, "escaped.txt").exists())
    }

    @Test
    fun rejectsWrongSha256() {
        val archive = zip("manifest.json" to "{}")
        val installer = RescuePluginArchiveInstaller(temporaryFolder.newFolder("digest"))

        val failure =
            runCatching {
                installer.extractAndValidate(archive, "00".repeat(32), "wuxianpi.test", "1.0.0")
            }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun rejectsArchiveManifestThatIsIncompatibleWithHost() {
        val archive =
            zip(
                "manifest.json" to
                    """{"schemaVersion":1,"id":"wuxianpi.test","version":"1.0.0","name":"Test","description":"Test plugin","category":"test","minHostVersion":14,"requiredCapabilities":[],"tags":[],"documents":[]}"""
            )
        val installer = RescuePluginArchiveInstaller(temporaryFolder.newFolder("incompatible"))

        val failure =
            runCatching {
                installer.extractAndValidate(
                    archive,
                    RescuePluginArchiveInstaller.sha256(archive),
                    "wuxianpi.test",
                    "1.0.0",
                )
            }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun rejectsArchiveManifestThatDiffersFromCatalogManifest() {
        val manifestJson =
            """{"schemaVersion":1,"id":"wuxianpi.test","version":"1.0.0","name":"Archive name","description":"Test plugin","category":"test","minHostVersion":12,"requiredCapabilities":[],"tags":[],"documents":[]}"""
        val archive = zip("manifest.json" to manifestJson)
        val catalogManifest =
            RescuePluginManifest.parse(JSONObject(manifestJson.replace("Archive name", "Catalog name")))
        val installer = RescuePluginArchiveInstaller(temporaryFolder.newFolder("catalog-mismatch"))

        val failure =
            runCatching {
                installer.extractAndValidate(
                    archive,
                    RescuePluginArchiveInstaller.sha256(archive),
                    "wuxianpi.test",
                    "1.0.0",
                    catalogManifest,
                )
            }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
