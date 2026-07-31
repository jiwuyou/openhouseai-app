package com.ai.assistance.operit.rescue.plugins

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
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
