package com.wuxianpi.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingInstallerScriptTest {
    @Test
    fun `installer accepts only arm64 and gives explicit unsupported message`() {
        val guard = arm64InstallerGuard()
        assertTrue(guard.contains("aarch64|arm64"))
        assertTrue(guard.contains("当前版本仅支持 ARM64"))
        assertTrue(guard.contains("exit 2"))
        assertFalse(guard.contains("armv7"))
        assertFalse(guard.contains("x86_64"))
    }

    @Test
    fun `installer requests only the delivered arm64 payload`() {
        assertEquals("runtime-aarch64.tgz", ARM64_RUNTIME_ASSET)
        assertFalse(ARM64_RUNTIME_ASSET.contains("armv7"))
        assertFalse(ARM64_RUNTIME_ASSET.contains("x86_64"))
    }
}
