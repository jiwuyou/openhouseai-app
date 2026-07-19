package com.wuxianpi.tools

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolContractTest {
    @Test
    fun `handler exception becomes structured error`() {
        val registry = AndroidToolRegistry().register("explode") { error("boom") }
        val result = registry.execute(ToolCall("c1", "explode", JSONObject()))
        assertTrue(result.isError)
        assertEquals("android_error", result.error?.code)
        assertEquals("boom", result.error?.message)
    }

    @Test
    fun `unknown tool does not throw`() {
        val result = AndroidToolRegistry().execute(ToolCall("c2", "missing", JSONObject()))
        assertTrue(result.isError)
        assertEquals("tool_not_found", result.error?.code)
    }

    @Test
    fun `success serializes without error`() {
        val json = ToolResult.success("c3", JSONObject().put("value", 1)).toJson()
        assertFalse(json.getBoolean("isError"))
        assertEquals(1, json.getJSONObject("content").getInt("value"))
    }
}
