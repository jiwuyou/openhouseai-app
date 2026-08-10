package com.ai.assistance.operit.rescue.plugins

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RescuePluginContractTest {
    @Test
    fun parsesFrozenV1Manifest() {
        val manifest =
            RescuePluginManifest.parse(
                JSONObject(
                    """{"schemaVersion":1,"id":"wuxianpi.first-install","version":"1.0.0","name":"First install","description":"Install WuxianPi","category":"setup","minHostVersion":12,"entryWorkflow":"workflows/install.json","documents":[{"path":"docs/README.md","title":"Guide"}],"requiredCapabilities":["termux"],"tags":["setup"]}"""
                )
            )

        assertEquals("workflows/install.json", manifest.entryWorkflow)
        assertEquals(listOf("docs/README.md"), manifest.documents.map { it.path })
        assertEquals("Guide", manifest.documents.single().title)
        assertTrue(manifest.assistantContexts.isEmpty())
        assertEquals("business", manifest.sessionRole)
        assertTrue(manifest.actions.isEmpty())
    }

    @Test
    fun parsesSessionRoleAndDynamicActions() {
        val manifest =
            RescuePluginManifest.parse(
                JSONObject(
                    """{"schemaVersion":1,"id":"wuxianpi.session-runtime","version":"1.0.0","name":"Runtime","description":"Runtime rules","category":"core","minHostVersion":13,"sessionRole":"runtime","actions":[{"id":"resource-update","title":"Check updates","icon":"refresh-cw","priority":90,"prompt":"Inspect resources","requiresPlugins":["wuxianpi.resource-update"]}],"documents":[],"requiredCapabilities":[],"tags":[]}"""
                )
            )

        assertEquals("runtime", manifest.sessionRole)
        assertEquals("resource-update", manifest.actions.single().id)
        assertEquals("always", manifest.actions.single().visibleWhen)
        assertEquals(listOf("wuxianpi.resource-update"), manifest.actions.single().requiresPlugins)
        assertThrows(IllegalArgumentException::class.java) {
            RescuePluginManifest.parse(
                JSONObject(
                    """{"schemaVersion":1,"id":"wuxianpi.bad","version":"1.0.0","name":"Bad","description":"Bad","category":"core","minHostVersion":13,"sessionRole":"loop","documents":[],"requiredCapabilities":[],"tags":[]}"""
                )
            )
        }
    }

    @Test
    fun parsesOptionalAssistantContextsAndRejectsInvalidDeclarations() {
        val manifest =
            RescuePluginManifest.parse(
                JSONObject(
                    """{"schemaVersion":1,"id":"wuxianpi.guide","version":"1.0.0","name":"Guide","description":"Guide plugin","category":"knowledge","minHostVersion":13,"assistantContexts":[{"path":"prompts/instruction.md","scope":"session"},{"path":"scripts/time.js","scope":"turn","provider":"javascript","function":"buildContext"}],"documents":[],"requiredCapabilities":[],"tags":[]}"""
                )
            )

        assertEquals(listOf("static", "javascript"), manifest.assistantContexts.map { it.provider })
        assertEquals(listOf("session", "turn"), manifest.assistantContexts.map { it.scope })
        assertEquals("buildContext", manifest.assistantContexts.last().functionName)
        assertThrows(IllegalArgumentException::class.java) {
            RescuePluginManifest.parse(
                JSONObject(
                    """{"schemaVersion":1,"id":"wuxianpi.guide","version":"1.0.0","name":"Guide","description":"Guide plugin","category":"knowledge","minHostVersion":13,"assistantContexts":[{"path":"../instruction.md","scope":"session"}],"documents":[],"requiredCapabilities":[],"tags":[]}"""
                )
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RescuePluginManifest.parse(
                JSONObject(
                    """{"schemaVersion":1,"id":"wuxianpi.guide","version":"1.0.0","name":"Guide","description":"Guide plugin","category":"knowledge","minHostVersion":13,"assistantContexts":[{"path":"prompts/instruction.md","scope":"session","extra":true}],"documents":[],"requiredCapabilities":[],"tags":[]}"""
                )
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RescuePluginManifest.parse(
                JSONObject(
                    """{"schemaVersion":1,"id":"wuxianpi.guide","version":"1.0.0","name":"Guide","description":"Guide plugin","category":"knowledge","minHostVersion":13,"assistantContexts":[{"path":"scripts/time.js","scope":"turn","provider":"javascript","function":"provider.build"}],"documents":[],"requiredCapabilities":[],"tags":[]}"""
                )
            )
        }
    }

    @Test
    fun selectsInstallableReleaseFromCanonicalHubCatalogPlugin() {
        val fixture =
            JSONObject(
                """{
                  "id":"wuxianpi.first-install",
                  "name":"First install catalog title",
                  "description":"Catalog description",
                  "category":"setup",
                  "tags":["setup"],
                  "latestVersion":"1.1.0",
                  "versions":[
                    {
                      "manifest":{"schemaVersion":1,"id":"wuxianpi.first-install","version":"1.0.0","name":"First install 1.0","description":"Old release","category":"setup","minHostVersion":12,"requiredCapabilities":[],"tags":[],"documents":[{"path":"docs/guide.md","title":"Guide"}]},
                      "sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                      "size":100,
                      "downloadUrl":"/plugins/wuxianpi.first-install/1.0.0.zip",
                      "documents":[],
                      "workflows":[]
                    },
                    {
                      "manifest":{"schemaVersion":1,"id":"wuxianpi.first-install","version":"1.1.0","name":"First install 1.1","description":"Latest release","category":"setup","minHostVersion":12,"requiredCapabilities":[],"tags":[],"documents":[{"path":"docs/guide.md","title":"Guide"}]},
                      "sha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                      "size":120,
                      "downloadUrl":"/plugins/wuxianpi.first-install/1.1.0.zip",
                      "documents":[],
                      "workflows":[]
                    }
                  ]
                }"""
            )

        val latest = RescuePluginListing.parseCatalogPlugin(fixture)
        val requested = RescuePluginListing.parseCatalogPlugin(fixture, "1.0.0")

        assertEquals("1.1.0", latest.version)
        assertEquals("Latest release", latest.description)
        assertEquals("b".repeat(64), latest.sha256)
        assertEquals("1.0.0", requested.version)
        assertEquals("/plugins/wuxianpi.first-install/1.0.0.zip", requested.downloadUrl)
    }

    @Test
    fun convertsCanonicalCommentVersionAtHttpBoundary() {
        val parsed =
            RescuePluginComment.parse(
                JSONObject(
                    """{"id":"comment-1","pluginId":"wuxianpi.first-install","version":"1.0.0","authorType":"agent","authorName":"Rescue Agent","clientId":"device","content":"Passed","rating":5,"environment":{"android":"14"},"createdAt":"2026-07-31T00:00:00Z"}"""
                )
            )
        val wire =
            buildCommentWirePayload(
                JSONObject()
                    .put("draftId", "local-only")
                    .put("pluginId", parsed.pluginId)
                    .put("pluginVersion", parsed.pluginVersion)
                    .put("authorType", parsed.authorType)
                    .put("authorName", parsed.authorName)
                    .put("content", parsed.content)
                    .put("rating", parsed.rating)
                    .put("environment", parsed.environment),
                "device-id",
            )

        assertEquals("1.0.0", parsed.pluginVersion)
        assertEquals("1.0.0", wire.getString("version"))
        assertEquals("device-id", wire.getString("clientId"))
        assertFalse(wire.has("pluginVersion"))
        assertFalse(wire.has("draftId"))
    }

    @Test
    fun declaresAllAgentPluginTools() {
        assertTrue(RescuePluginContract.TOOL_SEARCH in RescuePluginContract.toolNames)
        assertTrue(RescuePluginContract.TOOL_START_WORKFLOW in RescuePluginContract.toolNames)
        assertTrue(RescuePluginContract.TOOL_PUBLISH_COMMENT in RescuePluginContract.toolNames)
    }

    @Test
    fun declaresCurrentHostCompatibility() {
        assertEquals(13, RescuePluginContract.HOST_API_VERSION)
        assertEquals(
            setOf(
                "setup-tools",
                "termux",
                "persistent-terminal",
                "service-manager",
                "pi-model-api",
                "ubuntu",
            ),
            RescuePluginContract.supportedCapabilities,
        )
    }

    @Test
    fun acceptsEveryCapabilityUsedByOfficialMarketPlugins() {
        val officialCapabilities =
            setOf(
                "setup-tools",
                "termux",
                "persistent-terminal",
                "service-manager",
                "pi-model-api",
                "ubuntu",
            )

        officialCapabilities.forEach { capability ->
            val manifest = manifest(capabilities = listOf(capability))
            assertEquals(manifest, RescuePluginContract.requireCompatible(manifest))
        }
    }

    @Test
    fun comparesStrictSemanticVersionsForAutomaticUpdates() {
        assertEquals("1.0.0+build.1", RescuePluginContract.requireVersion("1.0.0+build.1"))
        assertTrue(RescuePluginContract.compareSemanticVersions("1.0.1", "1.0.0") > 0)
        assertTrue(RescuePluginContract.compareSemanticVersions("1.0.0", "1.0.0-rc.1") > 0)
        assertTrue(RescuePluginContract.compareSemanticVersions("1.0.0-rc.10", "1.0.0-rc.2") > 0)
        assertEquals(
            0,
            RescuePluginContract.compareSemanticVersions("1.0.0+build.2", "1.0.0+build.1"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            RescuePluginContract.requireVersion("01.0.0")
        }
    }

    @Test
    fun rejectsManifestThatNeedsNewerHostOrUnknownCapability() {
        val compatible = manifest(minHostVersion = 12, capabilities = listOf("termux"))
        assertEquals(compatible, RescuePluginContract.requireCompatible(compatible))

        assertThrows(IllegalArgumentException::class.java) {
            RescuePluginContract.requireCompatible(manifest(minHostVersion = 14))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RescuePluginContract.requireCompatible(
                manifest(capabilities = listOf("termux", "root-shell"))
            )
        }
    }

    private fun manifest(
        minHostVersion: Int = 12,
        capabilities: List<String> = emptyList(),
    ): RescuePluginManifest =
        RescuePluginManifest(
            id = "wuxianpi.first-install",
            version = "1.0.0",
            name = "First install",
            description = "Install WuxianPi",
            category = "setup",
            entryWorkflow = null,
            documents = emptyList(),
            requiredCapabilities = capabilities,
            tags = emptyList(),
            minHostVersion = minHostVersion,
            assistantContexts = emptyList(),
        )
}
