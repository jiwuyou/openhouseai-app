package com.ai.assistance.operit.rescue.pi

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
        assertTrue("create_terminal_session missing", "create_terminal_session" in names)
        assertTrue("execute_in_terminal_session missing", "execute_in_terminal_session" in names)
        assertTrue("execute_hidden_terminal_command missing", "execute_hidden_terminal_command" in names)
        assertTrue("execute_persistent_shell_command missing", "execute_persistent_shell_command" in names)
        assertTrue("input_in_terminal_session missing", "input_in_terminal_session" in names)
        assertTrue("close_terminal_session missing", "close_terminal_session" in names)
        assertTrue("get_terminal_session_screen missing", "get_terminal_session_screen" in names)
    }
}
