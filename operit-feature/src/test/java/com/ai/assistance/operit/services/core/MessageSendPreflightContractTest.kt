package com.ai.assistance.operit.services.core

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageSendPreflightContractTest {
    @Test
    fun `new chat is created only after model preflight succeeds`() {
        val sendUserMessage = methodSource(
            startMarker = "    fun sendUserMessage(",
            endMarker = "    suspend fun regenerateSingleAiMessage(",
        )

        assertOrdered(
            sendUserMessage,
            "resolvePreparedChatRequestOrReport(",
            "chatHistoryDelegate.createNewChat()",
        )
        assertTrue(sendUserMessage.contains("preparedChatRequest = preparedChatRequest"))
    }

    @Test
    fun `group orchestration and send state changes happen only after model preflight`() {
        val sendMessageInternal = methodSource(
            startMarker = "    private fun sendMessageInternal(",
            endMarker = "    private fun shouldRunGroupOrchestration(",
        )

        assertOrdered(
            sendMessageInternal,
            "val preparedChatRequestForSend =",
            "shouldRunGroupOrchestration(",
        )
        assertOrdered(
            sendMessageInternal,
            "val preparedChatRequestForSend =",
            "cancelPendingAutoContinuation(",
        )
        assertOrdered(
            sendMessageInternal,
            "val preparedChatRequestForSend =",
            "uiBridge.updateWebServerForCurrentChat(",
        )
    }

    @Test
    fun `group member preflight failure does not enter turn wait`() {
        val orchestration = methodSource(
            startMarker = "    private suspend fun orchestrateGroupConversation(",
            endMarker = "    private data class PlannedMember(",
        )

        assertOrdered(orchestration, "val memberSendStarted = sendMessageInternal(", "awaitTurnComplete(")
        assertOrdered(orchestration, "if (!memberSendStarted)", "awaitTurnComplete(")
        assertTrue(orchestration.contains("InputProcessingState.Idle"))
        assertTrue(orchestration.contains("return true"))
    }

    @Test
    fun `group orchestration rethrows cancellation instead of falling back`() {
        val sendMessageInternal = methodSource(
            startMarker = "    private fun sendMessageInternal(",
            endMarker = "    private fun shouldRunGroupOrchestration(",
        )

        assertOrdered(
            sendMessageInternal,
            "catch (e: CancellationException)",
            "catch (e: Exception)",
        )
        assertTrue(sendMessageInternal.contains("catch (e: CancellationException) {\n                        throw e"))
    }

    @Test
    fun `group response planner does not convert cancellation into planning failure`() {
        val planner = methodSource(
            startMarker = "    private suspend fun planResponseOrder(",
            endMarker = "    private fun parsePlannedRounds(",
        )

        assertFalse(planner.contains("runCatching"))
        assertTrue(planner.countOccurrences("catch (e: CancellationException)") == 3)
        assertTrue(planner.countOccurrences("throw e") == 3)
    }

    private fun methodSource(startMarker: String, endMarker: String): String {
        val source = sourceFile().readText()
        val start = source.indexOf(startMarker)
        val end = source.indexOf(endMarker, start + startMarker.length)
        assertTrue("missing start marker: $startMarker", start >= 0)
        assertTrue("missing end marker: $endMarker", end > start)
        return source.substring(start, end)
    }

    private fun sourceFile(): File {
        val relativePath =
            "src/main/java/com/ai/assistance/operit/services/core/MessageCoordinationDelegate.kt"
        return sequenceOf(File(relativePath), File("operit-feature", relativePath))
            .firstOrNull(File::isFile)
            ?: error("Cannot find $relativePath")
    }

    private fun assertOrdered(source: String, first: String, second: String) {
        val firstIndex = source.indexOf(first)
        val secondIndex = source.indexOf(second)
        assertTrue("missing marker: $first", firstIndex >= 0)
        assertTrue("missing marker: $second", secondIndex >= 0)
        assertTrue("expected '$first' before '$second'", firstIndex < secondIndex)
    }

    private fun String.countOccurrences(value: String): Int =
        windowed(value.length).count { it == value }
}
