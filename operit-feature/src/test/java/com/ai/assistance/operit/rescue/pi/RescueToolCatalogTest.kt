package com.ai.assistance.operit.rescue.pi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RescueToolCatalogTest {
    @Test
    fun defaultCatalogExposesAllHostTerminalBackends() {
        val names =
            RescueToolCatalog.default()
                .toJsonArray()
                .let { tools ->
                    (0 until tools.length()).mapTo(linkedSetOf()) { index ->
                        tools.getJSONObject(index).getString("name")
                    }
                }

        assertTrue("execute_android_command missing", "execute_android_command" in names)
        assertTrue("execute_termux_command missing", "execute_termux_command" in names)
        assertTrue("termux_exec_command missing", "termux_exec_command" in names)
        assertTrue("termux_write_stdin missing", "termux_write_stdin" in names)
        assertTrue("list_termux_exec_sessions missing", "list_termux_exec_sessions" in names)
        assertTrue("create_terminal_session missing", "create_terminal_session" in names)
        assertTrue("execute_in_terminal_session missing", "execute_in_terminal_session" in names)
        assertTrue("execute_hidden_terminal_command missing", "execute_hidden_terminal_command" in names)
        assertTrue("execute_persistent_shell_command missing", "execute_persistent_shell_command" in names)
        assertTrue("input_in_terminal_session missing", "input_in_terminal_session" in names)
        assertTrue("close_terminal_session missing", "close_terminal_session" in names)
        assertTrue("get_terminal_session_screen missing", "get_terminal_session_screen" in names)
    }

    @Test
    fun managedTermuxToolsExposeFrozenParametersAndBootstrapGuidance() {
        val definitions = RescueToolCatalog.default().toJsonArray()
        val byName =
            (0 until definitions.length()).associate { index ->
                definitions.getJSONObject(index).let { it.getString("name") to it }
            }

        val exec = requireNotNull(byName["termux_exec_command"])
        val execProperties = exec.getJSONObject("parameters").getJSONObject("properties")
        assertEquals(
            setOf("command", "working_directory", "yield_time_ms", "session_name"),
            execProperties.keys().asSequence().toSet(),
        )
        assertTrue(exec.getString("description").contains("pkg install -y tmux"))
        assertTrue(exec.getString("description").contains("Never fall back"))

        val write = requireNotNull(byName["termux_write_stdin"])
        val writeProperties = write.getJSONObject("parameters").getJSONObject("properties")
        assertEquals(
            setOf("session_id", "chars", "control", "yield_time_ms", "after_cursor"),
            writeProperties.keys().asSequence().toSet(),
        )

        val raw = requireNotNull(byName["execute_termux_command"])
        assertTrue(raw.getString("description").contains("does not terminate"))
    }
}
