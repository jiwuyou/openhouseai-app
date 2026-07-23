package com.ai.assistance.operit.pi

import com.ai.assistance.operit.services.core.planTurnCompletion
import com.ai.assistance.operit.data.model.PiModelBinding
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.coroutines.coroutineContext

class PiChatEngineBindingTest {
    @Test
    fun `prompt acceptance exposes exact user entry id`() {
        assertEquals("user-42", promptUserEntryId(JSONObject().put("userEntryId", "user-42")))
    }

    @Test
    fun `unique legacy prompt fallback is accepted`() {
        val entries = JSONArray()
            .put(userEntry("u1", "first"))
            .put(userEntry("u2", "target"))
        assertEquals("u2", selectUnambiguousUserEntryId(entries, "target"))
    }

    @Test
    fun `repeated identical legacy prompts fail safely`() {
        val entries = JSONArray()
            .put(userEntry("u1", "same prompt"))
            .put(userEntry("u2", "same   prompt"))
        assertThrows(IllegalStateException::class.java) {
            selectUnambiguousUserEntryId(entries, "same prompt")
        }
    }

    @Test
    fun `pi only turn still increments completion counter`() {
        val plan = planTurnCompletion(currentCount = 3, hasLegacyService = false)
        assertEquals(4L, plan.nextCount)
        assertFalse(plan.invokeLegacyHook)
    }

    @Test
    fun `overlapping same session send cannot consume first terminal event`() = runBlocking {
        withTimeout(5_000) {
            val gate = PiSessionTurnGate()
            val wireEvents = MutableSharedFlow<String>(extraBufferCapacity = 2)
            val firstStarted = CompletableDeferred<Unit>()
            val secondStarted = CompletableDeferred<Unit>()

            val first = launch {
                gate.run(requireNotNull(coroutineContext[Job])) {
                    val terminal = CompletableDeferred<String>()
                    val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                        wireEvents.collect { terminal.complete(it) }
                    }
                    firstStarted.complete(Unit)
                    assertEquals("agent_settled", terminal.await())
                    collector.cancel()
                }
            }
            firstStarted.await()

            val second = launch {
                gate.run(requireNotNull(coroutineContext[Job])) {
                    val terminal = CompletableDeferred<String>()
                    val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                        wireEvents.collect { terminal.complete(it) }
                    }
                    secondStarted.complete(Unit)
                    assertEquals("prompt_completed", terminal.await())
                    collector.cancel()
                }
            }

            yield()
            assertFalse("second turn subscribed before first settled", secondStarted.isCompleted)
            wireEvents.emit("agent_settled")
            first.join()
            secondStarted.await()
            wireEvents.emit("prompt_completed")
            second.join()
        }
    }

    @Test
    fun `session model binder covers create open fork and config override`() = runBlocking {
        val coding = PiModelBinding("anthropic", "claude-sonnet")
        val daily = PiModelBinding("deepseek", "deepseek-chat")
        val applied = mutableListOf<PiModelBinding>()
        val binder = PiSessionModelBinder()

        listOf("create", "open", "fork").forEach {
            binder.onSessionAttached()
            binder.ensure(coding, applied::add)
        }
        binder.ensure(daily, applied::add)
        binder.ensure(daily, applied::add)

        assertEquals(listOf(coding, coding, coding, daily), applied)
    }

    private fun userEntry(id: String, text: String): JSONObject =
        JSONObject()
            .put("id", id)
            .put(
                "message",
                JSONObject()
                    .put("role", "user")
                    .put("content", text),
            )
}
