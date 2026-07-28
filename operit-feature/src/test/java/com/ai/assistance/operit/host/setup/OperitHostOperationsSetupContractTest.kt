package com.ai.assistance.operit.host.setup

import android.content.ContextWrapper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OperitHostOperationsSetupContractTest {
    private val operations = TestOperitHostOperations()
    private val context = ContextWrapper(null)

    @Test
    fun frozenSetupMethodsDefaultToStructuredUnsupportedResults() = runBlocking {
        val results =
            listOf(
                WuxianPiSetupContract.OPERATION_INSPECT to operations.inspectWuxianPiSetup(),
                WuxianPiSetupContract.OPERATION_PREPARE_RUNTIME_HOST to
                    operations.prepareRuntimeHost(context),
                WuxianPiSetupContract.OPERATION_REQUEST_TERMUX_HOME_ACCESS to
                    operations.requestTermuxHomeAccess(context),
                WuxianPiSetupContract.OPERATION_REQUEST_TERMUX_RUN_COMMAND_PERMISSION to
                    operations.requestTermuxRunCommandPermission(context),
                WuxianPiSetupContract.OPERATION_PREPARE_PERSISTENT_TERMUX to
                    operations.preparePersistentTermux(),
                WuxianPiSetupContract.OPERATION_START_SETUP to operations.startWuxianPiSetup(),
                WuxianPiSetupContract.OPERATION_SETUP_STATUS to operations.wuxianPiSetupStatus(),
            )

        results.forEach { (operation, result) ->
            assertFalse(result.success)
            assertEquals(operation, result.details.getString("operation"))
            assertFalse(result.details.getBoolean("supported"))
            assertFalse(result.details.getBoolean("userActionRequired"))
        }
    }

    @Test
    fun externalHostActionsAreFrozen() {
        assertEquals("com.termux.SMALLPHONE_HOST", WuxianPiSetupContract.SMALLPHONE_HOST_ACTION)
        assertEquals(
            "com.termux.WUXIANPI_PREPARE_HOST",
            WuxianPiSetupContract.PREPARE_HOST_ACTION,
        )
    }
}
