package com.ai.assistance.operit.host.terminal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostTerminalAdapterTest {
    @Test
    fun commandFlowPassesTimeoutToBackendAndReportsTimeout() = runBlocking {
        val backend = RecordingBackend()
        val adapter = HostTerminalAdapter { backend }
        assertTrue(adapter.initialize())

        val events =
            adapter.executeCommandFlow("operit_v_u_0123456789abcdef01234567", "sleep 10", 4_321L)
                .toList()

        assertEquals(4_321L, backend.lastTimeoutMs)
        assertTrue(events.last().isCompleted)
        assertTrue(events.last().timedOut)
        assertEquals(-1, events.last().exitCode)
        assertFalse(events.first().timedOut)

        adapter.destroy()
        assertTrue(backend.destroyed)
    }

    @Test
    fun managedTermuxApiDelegatesNormalizedYieldAndCursor() = runBlocking {
        val backend = RecordingBackend()
        val adapter = HostTerminalAdapter { backend }

        val started = adapter.executeTermuxCommand(
            command = "sleep 30",
            workingDirectory = "\$HOME/work",
            yieldTimeMs = 999_999L,
            sessionName = "repair",
        )
        val continued = adapter.writeTermuxStdin(
            sessionId = started.sessionId!!,
            chars = "status",
            control = "enter",
            yieldTimeMs = 5_000L,
            afterCursor = 12L,
        )

        assertEquals(300_000L, backend.lastTermuxYieldMs)
        assertEquals("\$HOME/work", backend.lastWorkingDirectory)
        assertEquals("repair", backend.lastSessionName)
        assertEquals(12L, backend.lastCursor)
        assertEquals("next", continued.output)
    }

    private class RecordingBackend : HostTerminalSessionBackend {
        override val terminalState = MutableStateFlow(HostTerminalState())
        var lastTimeoutMs: Long? = null
        var destroyed = false

        override suspend fun initialize(): Boolean = true

        override suspend fun destroy() {
            destroyed = true
        }

        override suspend fun createSession(title: String): String = error("not used")

        override suspend fun closeSession(sessionId: String) = Unit

        override suspend fun executeCommand(
            sessionId: String,
            command: String,
            timeoutMs: Long,
            onOutput: suspend (String) -> Unit,
        ): HostTerminalHiddenResult {
            lastTimeoutMs = timeoutMs
            onOutput("partial")
            return HostTerminalHiddenResult(
                state = HostTerminalHiddenResult.State.TIMEOUT,
                exitCode = -1,
                output = "partial",
                error = "",
                rawOutputPreview = "partial",
                target = HostTerminalTarget.UBUNTU,
            )
        }

        override suspend fun executeHiddenCommand(
            command: String,
            executorKey: String,
            timeoutMs: Long,
        ): HostTerminalHiddenResult = error("not used")

        override suspend fun sendInput(
            sessionId: String,
            hasInput: Boolean,
            input: String,
            control: String?,
        ): Int = 0

        override suspend fun getSessionScreen(sessionId: String): HostTerminalScreenSnapshot =
            error("not used")

        var lastTermuxYieldMs: Long? = null
        var lastWorkingDirectory: String? = null
        var lastSessionName: String? = null
        var lastCursor: Long? = null

        override suspend fun executeTermuxCommand(
            command: String,
            workingDirectory: String?,
            yieldTimeMs: Long,
            sessionName: String?,
        ): HostTermuxExecResult {
            lastTermuxYieldMs = yieldTimeMs
            lastWorkingDirectory = workingDirectory
            lastSessionName = sessionName
            return HostTermuxExecResult(
                state = HostTermuxExecState.RUNNING,
                sessionId = "operit_v_t_0123456789abcdef01234567",
                persistent = true,
            )
        }

        override suspend fun writeTermuxStdin(
            sessionId: String,
            chars: String,
            control: String?,
            yieldTimeMs: Long,
            afterCursor: Long?,
        ): HostTermuxExecResult {
            lastCursor = afterCursor
            return HostTermuxExecResult(
                state = HostTermuxExecState.RUNNING,
                sessionId = sessionId,
                output = "next",
                cursor = 16L,
                persistent = true,
            )
        }

        override suspend fun listTermuxSessions(
            includeCompleted: Boolean,
        ): List<HostTermuxExecSession> = emptyList()

        override fun isConnected(): Boolean = true
    }
}
