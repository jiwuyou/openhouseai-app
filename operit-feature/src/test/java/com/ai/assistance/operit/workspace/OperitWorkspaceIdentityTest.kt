package com.ai.assistance.operit.workspace

import androidx.activity.OnBackPressedCallback
import com.ai.assistance.operit.api.chat.ChatRuntimeSlot
import com.ai.assistance.operit.data.preferences.ModelConfigStorageScope
import com.ai.assistance.operit.ui.main.OperitHostMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OperitWorkspaceIdentityTest {
    @Test
    fun `basic and rescue use isolated chat and model configuration slots`() {
        val basic = OperitHostMode.BASIC.workspaceIdentity()
        val rescue = OperitHostMode.RESCUE.workspaceIdentity()

        assertEquals(ChatRuntimeSlot.MAIN, basic.runtimeSlot)
        assertEquals(ModelConfigStorageScope.MAIN, basic.modelConfigStorageScope)
        assertEquals(ChatRuntimeSlot.RESCUE, rescue.runtimeSlot)
        assertEquals(ModelConfigStorageScope.RESCUE, rescue.modelConfigStorageScope)
        assertNotEquals(basic.chatViewModelKey, rescue.chatViewModelKey)
    }

    @Test
    fun `standalone keeps the established main runtime`() {
        val standalone = OperitHostMode.STANDALONE.workspaceIdentity()

        assertEquals(ChatRuntimeSlot.MAIN, standalone.runtimeSlot)
        assertEquals(ModelConfigStorageScope.MAIN, standalone.modelConfigStorageScope)
        assertEquals("operit-chat-main", standalone.chatViewModelKey)
    }

    @Test
    fun `embedded back dispatcher reports whether internal compose handled back`() {
        val embeddedDispatcher = EmbeddedBackDispatcher()

        assertFalse(embeddedDispatcher.dispatch())

        var handled = false
        val callback =
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    handled = true
                }
            }
        embeddedDispatcher.dispatcher.addCallback(callback)

        assertTrue(embeddedDispatcher.dispatch())
        assertTrue(handled)

        callback.isEnabled = false
        assertFalse(embeddedDispatcher.dispatch())
    }
}
