package com.wuxianpi.openhouse.feature

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FloatingWindowStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun savesAndRestoresTheMostRecentFloatingPage() {
        val store = FloatingWindowStore(context)
        store.clear()
        val snapshot = FloatingWindowSnapshot(
            title = "AI 对话",
            conversationUrl = "https://example.test/ai/task-1",
            returnUrl = "https://example.test/app/item-1",
            x = 12,
            y = 34,
            width = 320,
            height = 480,
            updatedAt = 123L,
        )

        store.save(snapshot)

        assertEquals(snapshot, store.load())
        store.clear()
        assertNull(store.load())
    }

    @Test
    fun ignoresMalformedStoredSnapshot() {
        val store = FloatingWindowStore(context)
        context.getSharedPreferences("openhouse-floating-window", Context.MODE_PRIVATE)
            .edit()
            .putString("last", "not-json")
            .commit()

        assertNull(store.load())
    }
}
