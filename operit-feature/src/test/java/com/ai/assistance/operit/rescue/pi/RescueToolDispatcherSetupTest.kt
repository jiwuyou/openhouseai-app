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
        val dispatcher = RescueToolDispatcher(ContextWrapper(null)) { host }

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

    private class UserActionHostOperations : TestOperitHostOperations() {
        var requestCount = 0

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
    }
}
