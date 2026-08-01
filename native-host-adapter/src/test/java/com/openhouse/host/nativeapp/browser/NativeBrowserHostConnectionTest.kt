package com.openhouse.host.nativeapp.browser

import com.wuxianpi.browser.host.BrowserHostDescription
import com.wuxianpi.browser.host.BrowserHostEvent
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeBrowserHostConnectionTest {
    @Test
    fun `runtime endpoint becomes browser websocket endpoint`() {
        assertEquals(
            "ws://127.0.0.1:20765/v1/browser-host",
            browserWebSocketUrl("http://127.0.0.1:20765/"),
        )
        assertEquals(
            "wss://example.com/runtime/v1/browser-host",
            browserWebSocketUrl("https://example.com/runtime"),
        )
    }

    @Test
    fun `registration matches runtime v1 top level contract`() {
        val tabs = JSONArray().put(
            JSONObject()
                .put("tabId", "tab-1")
                .put("active", true)
                .put("url", "https://example.com")
                .put("context", JSONObject().put("appId", "memo")),
        )
        val registration = buildBrowserRegistration(
            BrowserHostDescription.nativeHost().toJson(),
            "0.2.0",
            tabs,
            JSONObject().put("appId", "memo"),
        )

        assertEquals("browser.register", registration.getString("type"))
        assertEquals("wuxianpi-browser-host-v1", registration.getString("protocol"))
        assertEquals(1, registration.getInt("protocolVersion"))
        assertEquals("native-browser", registration.getString("hostId"))
        assertEquals(200, registration.getInt("priority"))
        assertEquals("0.2.0", registration.getString("implementationVersion"))
        assertTrue(registration.get("capabilities") is JSONObject)
        assertEquals("tab-1", registration.getJSONArray("tabs").getJSONObject(0).getString("tabId"))
        assertEquals("memo", registration.getJSONObject("context").getString("appId"))
        assertFalse(registration.has("host"))
        assertFalse(registration.has("state"))
    }

    @Test
    fun `event carries runtime cache snapshots`() {
        val tabs = JSONArray().put(
            JSONObject().put("tabId", "tab-2").put("active", true),
        )
        val context = JSONObject().put("appId", "notes")
        val event = buildBrowserEvent(
            BrowserHostEvent("tab.activated", JSONObject().put("tabId", "tab-2")),
            tabs,
            context,
        )

        assertEquals("browser.event", event.getString("type"))
        assertEquals("tab.activated", event.getString("event"))
        assertEquals("tab-2", event.getString("tabId"))
        assertEquals("tab-2", event.getJSONArray("tabs").getJSONObject(0).getString("tabId"))
        assertEquals("notes", event.getJSONObject("context").getString("appId"))
    }
}
