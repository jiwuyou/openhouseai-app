package com.wuxianpi.ai

import com.wuxianpi.pi.PiSessionRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecentSessionStoreTest {
    private class MemoryPreferences : StringPreferenceStore {
        val values = mutableMapOf<String, String>()
        override fun get(key: String): String? = values[key]
        override fun put(key: String, value: String) { values[key] = value }
        override fun remove(key: String) { values.remove(key) }
    }

    @Test
    fun `session path survives store recreation and clears explicitly`() {
        val preferences = MemoryPreferences()
        val first = RecentSessionStore.forTest(preferences)
        val expected = PiSessionRef("session-1", "/data/data/com.termux/files/home/.pi/agent/s.jsonl", "/work")
        first.save("http://127.0.0.1:8765/", expected)

        val recreated = RecentSessionStore.forTest(preferences)
        assertEquals(expected, recreated.load("http://127.0.0.1:8765"))
        recreated.clear("http://127.0.0.1:8765/")
        assertNull(recreated.load("http://127.0.0.1:8765/"))
    }

    @Test
    fun `different services cannot overwrite each others recent session`() {
        assertNotEquals(
            RecentSessionStore.key("http://127.0.0.1:8765/"),
            RecentSessionStore.key("http://127.0.0.1:9765/"),
        )
    }
}
