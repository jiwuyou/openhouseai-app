package com.ai.assistance.operit.rescue.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RescueFirstUsePromptTest {
    @Test
    fun fixedPromptMatchesFirstUseRequest() {
        assertEquals(
            "这是我第一次使用。请先调用 start_rescue_plugin_workflow 启动内置的 wuxianpi.first-install 工作流，再按工作流检查当前环境并引导我完成 WuxianPi 初始化。",
            RESCUE_FIRST_USE_MESSAGE,
        )
    }

    @Test
    fun onlyShowsForLoadedEmptyRescueConversation() {
        assertTrue(
            shouldShowRescueFirstUsePrompt(
                isRescueContext = true,
                hasCurrentConversation = true,
                persistedMessageCount = 0,
                visibleMessageCount = 0,
                isHistoryLoading = false,
            )
        )
        assertFalse(
            shouldShowRescueFirstUsePrompt(
                isRescueContext = false,
                hasCurrentConversation = true,
                persistedMessageCount = 0,
                visibleMessageCount = 0,
                isHistoryLoading = false,
            )
        )
        assertFalse(
            shouldShowRescueFirstUsePrompt(
                isRescueContext = true,
                hasCurrentConversation = true,
                persistedMessageCount = 1,
                visibleMessageCount = 0,
                isHistoryLoading = false,
            )
        )
        assertFalse(
            shouldShowRescueFirstUsePrompt(
                isRescueContext = true,
                hasCurrentConversation = true,
                persistedMessageCount = 0,
                visibleMessageCount = 1,
                isHistoryLoading = false,
            )
        )
        assertFalse(
            shouldShowRescueFirstUsePrompt(
                isRescueContext = true,
                hasCurrentConversation = true,
                persistedMessageCount = null,
                visibleMessageCount = 0,
                isHistoryLoading = true,
            )
        )
    }
}
