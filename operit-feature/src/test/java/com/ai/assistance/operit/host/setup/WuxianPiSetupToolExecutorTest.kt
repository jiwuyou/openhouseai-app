package com.ai.assistance.operit.host.setup

import android.content.Context
import android.content.ContextWrapper
import com.ai.assistance.operit.host.OperitHostOperationResult
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class WuxianPiSetupToolExecutorTest {
    @Test
    fun routesEverySetupToolToTheActiveHost() = runBlocking {
        val host = RecordingSetupHostOperations()
        val executor = WuxianPiSetupToolExecutor { host }
        val context = ContextWrapper(null)

        (WuxianPiSetupContract.toolNames - WuxianPiSetupContract.TOOL_OPEN_WUXIANPI).forEach { toolName ->
            val args =
                if (toolName == WuxianPiSetupContract.TOOL_WRITE_SERVICE_MANAGER_CONNECTION) {
                    JSONObject()
                        .put("serviceManagerBaseUrl", "http://127.0.0.1:20087")
                        .put("token", "test-token")
                } else {
                    JSONObject()
                }
            val result = executor.execute(toolName, context, args)
            assertEquals(toolName, result.details.getString("called"))
        }
        assertEquals(WuxianPiSetupContract.toolNames - WuxianPiSetupContract.TOOL_OPEN_WUXIANPI, host.calls.toSet())

        val openResult = executor.execute(WuxianPiSetupContract.TOOL_OPEN_WUXIANPI, context)
        assertEquals(WuxianPiSetupContract.OPERATION_OPEN_WUXIANPI, openResult.details.getString("operation"))
        assertEquals(true, openResult.details.getBoolean("userActionRequired"))
        assertEquals(
            "首次安装已完成。\n\n以后请直接使用 WuxianPi 完成日常对话、安装更新和问题处理。\n维修助手主要用于首次安装和故障修复，和apk更新。",
            openResult.details.getString("actionDescription"),
        )
    }

    private class RecordingSetupHostOperations : TestOperitHostOperations() {
        val calls = mutableListOf<String>()

        override suspend fun inspectWuxianPiSetup() = called(WuxianPiSetupContract.TOOL_INSPECT)

        override fun prepareRuntimeHost(context: Context) =
            called(WuxianPiSetupContract.TOOL_PREPARE_RUNTIME_HOST)

        override fun requestTermuxHomeAccess(context: Context) =
            called(WuxianPiSetupContract.TOOL_REQUEST_TERMUX_HOME_ACCESS)

        override fun requestTermuxRunCommandPermission(context: Context) =
            called(WuxianPiSetupContract.TOOL_REQUEST_TERMUX_RUN_COMMAND_PERMISSION)

        override suspend fun configureTermuxExternalApps() =
            called(WuxianPiSetupContract.TOOL_CONFIGURE_TERMUX_EXTERNAL_APPS)

        override suspend fun verifyTermuxRunCommand() =
            called(WuxianPiSetupContract.TOOL_VERIFY_TERMUX_RUN_COMMAND)

        override suspend fun preparePersistentTermux() =
            called(WuxianPiSetupContract.TOOL_PREPARE_PERSISTENT_TERMUX)

        override suspend fun startWuxianPiSetup() =
            called(WuxianPiSetupContract.TOOL_START_SETUP)

        override suspend fun wuxianPiSetupStatus() =
            called(WuxianPiSetupContract.TOOL_SETUP_STATUS)

        override suspend fun storeServiceManagerConnection() =
            called(WuxianPiSetupContract.TOOL_STORE_SERVICE_MANAGER_CONNECTION)

        override suspend fun ensureOpenHouseConnectionBridge() =
            called(WuxianPiSetupContract.TOOL_ENSURE_OPENHOUSE_CONNECTION_BRIDGE)

        override suspend fun writeServiceManagerConnection(
            serviceManagerBaseUrl: String,
            token: String,
        ) = called(WuxianPiSetupContract.TOOL_WRITE_SERVICE_MANAGER_CONNECTION)

        private fun called(name: String): OperitHostOperationResult {
            calls += name
            return OperitHostOperationResult(true, JSONObject().put("called", name))
        }
    }
}
