package com.ai.assistance.operit.host.terminal.tmux

import com.ai.assistance.operit.host.terminal.HostTerminalTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TermuxSessionProtocolTest {
    @Test
    fun ubuntuSessionNamesProduceStableStrictIds() {
        val visible =
            TermuxSessionProtocol.sessionIdForName("rescue_default_session")
        val hidden =
            TermuxSessionProtocol.hiddenSessionIdForKey("mcp:default")

        assertEquals(
            visible,
            TermuxSessionProtocol.sessionIdForName("rescue_default_session"),
        )
        assertTrue(visible.matches(Regex("^operit_v_u_[0-9a-f]{24}$")))
        assertTrue(hidden.matches(Regex("^operit_h_u_[0-9a-f]{24}$")))
        assertEquals(HostTerminalTarget.UBUNTU, TermuxSessionProtocol.targetForSessionId(visible))
    }

    @Test
    fun sessionNamesRejectShellSyntaxAndInvalidIds() {
        assertThrows(IllegalArgumentException::class.java) {
            TermuxSessionProtocol.sessionIdForName("session; rm -rf /")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TermuxSessionProtocol.requireSessionId("operit_v_u_deadbeef;kill-server")
        }
    }

    @Test
    fun payloadEncodesCommandAndCaptureParserPreservesExitCode() {
        val command = "printf '%s\\n' \"a'b\"; cd \"\$HOME/work dir\""
        val payload = TermuxSessionProtocol.commandPayload(command, "token123")
        assertFalse(payload.contains(command))
        assertTrue(payload.contains(TermuxSessionProtocol.base64(command)))

        val parsed =
            TermuxSessionProtocol.parseCapture(
                "noise\n${TermuxSessionProtocol.beginMarker("token123")}\nfirst\nsecond\n" +
                    "${TermuxSessionProtocol.endMarker("token123")}:17\n",
                "token123",
            )
        assertEquals("first\nsecond", parsed.output)
        assertEquals(17, parsed.exitCode)
        assertTrue(parsed.complete)
    }

    @Test
    fun ubuntuBootstrapAndControlKeysAreExplicit() {
        val bootstrap =
            TermuxSessionProtocol.ubuntuShellCommand("/data/data/com.termux/files/usr")
        assertTrue(bootstrap.contains("proot-distro"))
        assertTrue(bootstrap.contains("ubuntu"))
        assertEquals("enter", TermuxSessionProtocol.normalizeControl("RETURN"))
        assertEquals("Enter", TermuxSessionProtocol.controlKey("enter"))
    }
}
