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

    @Test
    fun `native pairing always targets the persistent service port`() {
        assertEquals(8765, WUXIANPI_NODE_PORT)
    }

    @Test
    fun `pairing listener and generated command use the same ipv4 loopback`() {
        val address = pairingInstallerBindAddress()
        assertEquals("127.0.0.1", PAIRING_INSTALLER_HOST)
        assertEquals(PAIRING_INSTALLER_HOST, address.hostAddress)
        assertEquals(4, address.address.size)
        assertTrue(pairingInstallerCommand(12345, "one-time").contains("http://127.0.0.1:12345/"))
    }

    @Test
    fun `installer bootstraps the minimum supported node and has a fallback package`() {
        val script = nodeBootstrapScript()
        assertTrue(script.contains("major===22&&minor>=19"))
        assertTrue(script.contains("pkg install -y nodejs-lts || true"))
        assertTrue(script.contains("if ! node_is_compatible; then\n  pkg install -y nodejs"))
        assertTrue(script.contains("Node.js >= 22.19"))
    }

    @Test
    fun `health readiness requires the node sdk protocol`() {
        val script = serviceCompatibilityScript()
        assertTrue(script.contains("wuxianpi-sdk-v1"))
        assertTrue(script.contains("/admin/v1/health"))
    }

    @Test
    fun `native payload installs from temporary extraction without duplicating sources in home`() {
        val commands = nativePayloadInstallCommands()
        assertTrue(commands.contains("-C \"${'$'}TMP\""))
        assertTrue(commands.contains("\"${'$'}TMP/install.sh\""))
        assertFalse(commands.contains("${'$'}ROOT/install.sh"))
    }
}
