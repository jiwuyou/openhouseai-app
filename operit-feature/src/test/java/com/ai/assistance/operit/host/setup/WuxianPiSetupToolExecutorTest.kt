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

        WuxianPiSetupContract.toolNames.forEach { toolName ->
            val result = executor.execute(toolName, context)
            assertEquals(toolName, result.details.getString("called"))
        }
        assertEquals(WuxianPiSetupContract.toolNames.toList(), host.calls)
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

        private fun called(name: String): OperitHostOperationResult {
            calls += name
            return OperitHostOperationResult(true, JSONObject().put("called", name))
        }
    }
}
