package com.ai.assistance.operit.rescue.pi

import android.content.Context
import android.content.ContextWrapper
import com.ai.assistance.operit.host.OperitHostOperationResult
import com.ai.assistance.operit.host.setup.TestOperitHostOperations
import com.ai.assistance.operit.host.setup.WuxianPiSetupContract
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RescueToolDispatcherSetupTest {
    @Test
    fun preservesStructuredDetailsAndDoesNotTreatUserActionAsFailure() = runBlocking {
        val host = UserActionHostOperations()
        val dispatcher =
            RescueToolDispatcher(
                context = ContextWrapper(null),
                operationsProvider = { host },
            )

        val completion =
            dispatcher.execute(
                catalog = RescueToolCatalog.default(),
                toolName = WuxianPiSetupContract.TOOL_REQUEST_TERMUX_HOME_ACCESS,
                args = JSONObject(),
                onUpdate = {},
            )

        assertEquals(1, host.requestCount)
        assertTrue(completion.userActionRequired)
        assertFalse(completion.isError)
        assertNull(completion.error)
        assertEquals("termux-home:", completion.details.getJSONObject("request").getString("root"))
        assertTrue(completion.toJson().getBoolean("userActionRequired"))
    }

    @Test
    fun nativePermissionToolTriggersTheRealHostFlowImmediately() = runBlocking {
        val host = UserActionHostOperations()
        val dispatcher =
            RescueToolDispatcher(
                context = ContextWrapper(null),
                operationsProvider = { host },
                deferUserActions = true,
            )

        val completion =
            dispatcher.execute(
                catalog = RescueToolCatalog.default(),
                toolName = WuxianPiSetupContract.TOOL_REQUEST_TERMUX_HOME_ACCESS,
                args = JSONObject(),
                onUpdate = {},
            )

        assertEquals(1, host.requestCount)
        assertTrue(completion.userActionRequired)
        assertFalse(completion.isError)
        assertFalse(completion.details.has(WuxianPiSetupContract.DETAIL_DEFERRED_USER_ACTION))
    }

    @Test
    fun nativeConfigureExternalAppsExecutesBeforeTheReloadAndVerifyStages() = runBlocking {
        val host = UserActionHostOperations()
        val dispatcher =
            RescueToolDispatcher(
                context = ContextWrapper(null),
                operationsProvider = { host },
                deferUserActions = true,
            )

        val completion =
            dispatcher.execute(
                catalog = RescueToolCatalog.default(),
                toolName = WuxianPiSetupContract.TOOL_CONFIGURE_TERMUX_EXTERNAL_APPS,
                args = JSONObject(),
                onUpdate = {},
            )

        assertEquals(1, host.configureCount)
        assertTrue(completion.userActionRequired)
        assertFalse(completion.isError)
        assertFalse(completion.details.has(WuxianPiSetupContract.DETAIL_DEFERRED_USER_ACTION))
        assertEquals("reload_termux_settings", completion.details.getString("action"))
    }

    @Test
    fun setupPermissionConfigureAndVerifyRemainAnExplicitOrderedFlow() = runBlocking {
        val host = OrderedSetupHostOperations()
        val dispatcher = RescueToolDispatcher(
            context = ContextWrapper(null),
            operationsProvider = { host },
            deferUserActions = true,
        )
        val catalog = RescueToolCatalog.default()

        dispatcher.execute(catalog, WuxianPiSetupContract.TOOL_REQUEST_TERMUX_RUN_COMMAND_PERMISSION, JSONObject(), onUpdate = {})
        dispatcher.execute(catalog, WuxianPiSetupContract.TOOL_CONFIGURE_TERMUX_EXTERNAL_APPS, JSONObject(), onUpdate = {})
        dispatcher.execute(catalog, WuxianPiSetupContract.TOOL_VERIFY_TERMUX_RUN_COMMAND, JSONObject(), onUpdate = {})

        assertEquals(
            listOf(
                WuxianPiSetupContract.TOOL_REQUEST_TERMUX_RUN_COMMAND_PERMISSION,
                WuxianPiSetupContract.TOOL_CONFIGURE_TERMUX_EXTERNAL_APPS,
                WuxianPiSetupContract.TOOL_VERIFY_TERMUX_RUN_COMMAND,
            ),
            host.calls,
        )
        assertEquals(0, host.probeExecutionsBeforeVerify)
    }

    @Test
    fun satisfiedOfferGateRequiresEveryRealStatusAndMatchingIdentity() {
        val offer = JSONObject()
            .put("offerId", "offer-126")
            .put("resourceSet", JSONObject().put("sequence", 2026081201L))
        val status = JSONObject().put(
            "status",
            JSONObject()
                .put("offerId", "offer-126")
                .put("resourceSetSequence", 2026081201L)
                .put("delivery", "ready")
                .put("content", "installed")
                .put("activation", "ready")
                .put("checks", JSONObject()
                    .put("canonicalAuth", "ok")
                    .put("serviceList", "ok")
                    .put("registryFile", "ok")
                    .put("registryApi", "ok")
                    .put("wuxianpiHealth", "healthy")),
        )

        val verification = verifySatisfiedApkResourceOffer(offer, status)

        assertTrue(verification.failures.toString(), verification.accepted)
        assertTrue(verification.failures.isEmpty())
    }

    @Test
    fun satisfiedOfferGateRejectsMissingEvidenceInsteadOfTrustingDetail() {
        val offer = JSONObject()
            .put("offerId", "offer-126")
            .put("resourceSet", JSONObject().put("sequence", 2026081201L))
        val status = JSONObject()
            .put("delivery", "ready")
            .put("content", "installed")
            .put("activation", "failed")
            .put("offerId", "wrong-offer")
            .put("resourceSetSequence", 2026081200L)

        val verification = verifySatisfiedApkResourceOffer(offer, status)

        assertFalse(verification.accepted)
        assertTrue(verification.failures.any { it.startsWith("activation=") })
        assertTrue(verification.failures.any { it.startsWith("offerId=") })
        assertTrue(verification.failures.any { it.startsWith("sequence=") })
        assertTrue(verification.failures.any { it.startsWith("canonicalAuth=") })
        assertTrue(verification.failures.any { it.startsWith("serviceList=") })
        assertTrue(verification.failures.any { it.startsWith("registryFile=") })
        assertTrue(verification.failures.any { it.startsWith("wuxianpiHealth=") })
    }

    private class UserActionHostOperations : TestOperitHostOperations() {
        var requestCount = 0
        var configureCount = 0

        override fun requestTermuxHomeAccess(context: Context): OperitHostOperationResult {
            requestCount += 1
            return OperitHostOperationResult(
                success = false,
                details =
                    JSONObject()
                        .put(WuxianPiSetupContract.DETAIL_USER_ACTION_REQUIRED, true)
                        .put("request", JSONObject().put("root", "termux-home:")),
                message = "Choose Termux Home",
            )
        }

        override suspend fun configureTermuxExternalApps(): OperitHostOperationResult {
            configureCount += 1
            return OperitHostOperationResult(
                success = true,
                details =
                    JSONObject()
                        .put(WuxianPiSetupContract.DETAIL_USER_ACTION_REQUIRED, true)
                        .put("propertiesReadable", true)
                        .put("parentWritable", true)
                        .put("allowExternalApps", true)
                        .put("duplicateCount", 0)
                        .put("action", "reload_termux_settings")
                        .put("command", "termux-reload-settings"),
                message = "Run termux-reload-settings in Termux",
            )
        }
    }

    private class OrderedSetupHostOperations : TestOperitHostOperations() {
        val calls = mutableListOf<String>()
        var probeExecutionsBeforeVerify = 0

        override fun requestTermuxRunCommandPermission(context: Context): OperitHostOperationResult {
            calls += WuxianPiSetupContract.TOOL_REQUEST_TERMUX_RUN_COMMAND_PERMISSION
            return OperitHostOperationResult(
                success = false,
                details = JSONObject().put(WuxianPiSetupContract.DETAIL_USER_ACTION_REQUIRED, true),
                message = "permission requested",
            )
        }

        override suspend fun configureTermuxExternalApps(): OperitHostOperationResult {
            calls += WuxianPiSetupContract.TOOL_CONFIGURE_TERMUX_EXTERNAL_APPS
            return OperitHostOperationResult(
                success = true,
                details = JSONObject()
                    .put(WuxianPiSetupContract.DETAIL_USER_ACTION_REQUIRED, true)
                    .put("action", "reload_termux_settings"),
            )
        }

        override suspend fun verifyTermuxRunCommand(): OperitHostOperationResult {
            calls += WuxianPiSetupContract.TOOL_VERIFY_TERMUX_RUN_COMMAND
            return OperitHostOperationResult(true, JSONObject().put("verified", true))
        }
    }
}
