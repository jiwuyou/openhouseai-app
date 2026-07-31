package com.ai.assistance.operit.rescue.plugins

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RescuePluginCommentDraftStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun savesAgentDraftLocallyUntilExplicitDeletion() {
        val root = temporaryFolder.newFolder("drafts")
        val store = RescuePluginCommentDraftStore(root)

        val draft =
            store.create(
                pluginId = "wuxianpi.first-install",
                pluginVersion = "1.0.0",
                type = "compatibility_report",
                rating = 5,
                content = "Android 14 installation completed",
                environment = JSONObject().put("android", "14"),
            )
        val draftId = draft.getString("draftId")

        assertEquals("agent", store.get(draftId).getString("authorType"))
        assertEquals(1, root.listFiles().orEmpty().size)
        store.delete(draftId)
        assertFalse(root.resolve("$draftId.json").exists())
    }

    @Test
    fun rejectsBlankContentAndInvalidRating() {
        val store = RescuePluginCommentDraftStore(temporaryFolder.newFolder("invalid"))

        assertTrue(
            runCatching {
                store.create("wuxianpi.test", "1.0.0", "feedback", 0, "ok", JSONObject())
            }.isFailure
        )
        assertTrue(
            runCatching {
                store.create("wuxianpi.test", "1.0.0", "feedback", 5, " ", JSONObject())
            }.isFailure
        )
    }
}
