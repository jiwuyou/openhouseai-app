package com.openhouse.host.nativeapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeControlPlaneBridgeTest {
    @Test
    fun runCommandExecutesTheFixedEntryDirectly() {
        val request = buildNativeControlPlaneStartRequest()

        assertEquals(
            "/data/data/com.termux/files/usr/bin/openhouse-control-plane-start",
            request.executable,
        )
        assertEquals(emptyList<String>(), request.arguments)
        assertNull(request.stdin)
        assertEquals("/data/data/com.termux/files/home", request.workingDirectory)
    }
}
