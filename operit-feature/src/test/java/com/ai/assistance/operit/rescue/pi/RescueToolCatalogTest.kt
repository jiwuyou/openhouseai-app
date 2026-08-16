package com.ai.assistance.operit.rescue.pi

import com.ai.assistance.operit.rescue.plugins.RescuePluginContract
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
        assertTrue("inspect_wuxianpi_setup missing", "inspect_wuxianpi_setup" in names)
        assertTrue("prepare_runtime_host missing", "prepare_runtime_host" in names)
        assertTrue("request_termux_home_access missing", "request_termux_home_access" in names)
        assertTrue(
            "request_termux_run_command_permission missing",
            "request_termux_run_command_permission" in names,
        )
        assertTrue("configure_termux_external_apps missing", "configure_termux_external_apps" in names)
        assertTrue("verify_termux_run_command missing", "verify_termux_run_command" in names)
        assertTrue("prepare_persistent_termux missing", "prepare_persistent_termux" in names)
        assertTrue("start_wuxianpi_setup missing", "start_wuxianpi_setup" in names)
        assertTrue("get_wuxianpi_setup_status missing", "get_wuxianpi_setup_status" in names)
        assertTrue("open_wuxianpi missing", "open_wuxianpi" in names)
        assertTrue(
            "store_service_manager_connection missing",
            "store_service_manager_connection" in names,
        )
        RescuePluginContract.toolNames.forEach { name ->
            assertTrue("$name missing", name in names)
        }
    }

    @Test
    fun managedTermuxToolsExposeFrozenParametersAndBootstrapGuidance() {
        val definitions = RescueToolCatalog.default(useTermuxHomeRepository = true).toJsonArray()
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

    @Test
    fun setupToolsAreParameterlessAndDescribeDurableCoreInstallation() {
        val definitions = RescueToolCatalog.default(useTermuxHomeRepository = true).toJsonArray()
        val byName =
            (0 until definitions.length()).associate { index ->
                definitions.getJSONObject(index).let { it.getString("name") to it }
            }
        val setupNames =
            listOf(
                "inspect_wuxianpi_setup",
                "prepare_runtime_host",
                "request_termux_home_access",
                "request_termux_run_command_permission",
                "configure_termux_external_apps",
                "verify_termux_run_command",
                "prepare_persistent_termux",
                "start_wuxianpi_setup",
                "get_wuxianpi_setup_status",
                "store_service_manager_connection",
            )

        setupNames.forEach { name ->
            val parameters = requireNotNull(byName[name]).getJSONObject("parameters")
            assertEquals(0, parameters.getJSONObject("properties").length())
            assertEquals(0, parameters.getJSONArray("required").length())
        }
        assertTrue(requireNotNull(byName["prepare_persistent_termux"]).getString("description").contains("tmux"))
        assertTrue(requireNotNull(byName["start_wuxianpi_setup"]).getString("description").contains("five core resources"))
        assertTrue(requireNotNull(byName["start_wuxianpi_setup"]).getString("description").contains("Ubuntu is not part"))
        assertTrue(requireNotNull(byName["get_wuxianpi_setup_status"]).getString("description").contains("durable"))
        assertTrue(
            requireNotNull(byName["store_service_manager_connection"])
                .getString("description")
                .contains("Android-private storage"),
        )
        val fileEnvironment = requireNotNull(byName["read_file"])
            .getJSONObject("parameters")
            .getJSONObject("properties")
            .getJSONObject("environment")
        assertEquals("repo:termux-home", fileEnvironment.getString("default"))
    }

    @Test
    fun allInOneCatalogKeepsDirectFileEnvironment() {
        val definitions = RescueToolCatalog.default().toJsonArray()
        val readFile = (0 until definitions.length())
            .map { definitions.getJSONObject(it) }
            .first { it.getString("name") == "read_file" }
        val environment = readFile.getJSONObject("parameters")
            .getJSONObject("properties")
            .getJSONObject("environment")

        assertTrue(!environment.has("default"))
    }
}
