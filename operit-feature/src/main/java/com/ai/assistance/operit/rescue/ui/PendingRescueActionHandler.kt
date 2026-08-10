package com.ai.assistance.operit.rescue.ui

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class PendingRescueAction(val id: String, val prompt: String)

/** Process-local handoff from RescueActivity intent parsing to the normal chat surface. */
object PendingRescueActionHandler {
    private val _pending = MutableStateFlow<PendingRescueAction?>(null)
    val pending: StateFlow<PendingRescueAction?> = _pending

    fun set(id: String, prompt: String) {
        if (id.isNotBlank() && prompt.isNotBlank()) {
            _pending.value = PendingRescueAction(id.trim(), prompt.trim())
        }
    }

    fun clear(id: String) {
        if (_pending.value?.id == id) _pending.value = null
    }
}

/** Persists one resumable normal-chat target for each dynamic Rescue action. */
object RescueActionConversationStore {
    private const val PREFERENCES = "rescue_action_conversations"
    private const val ACTIVE = "active"
    private const val COMPLETED = "completed"
    private const val FAILED = "failed"

    fun activeChatId(context: Context, actionId: String): String? {
        val preferences = preferences(context)
        return preferences.getString(chatIdKey(actionId), null)
            ?.takeIf { preferences.getString(stateKey(actionId), null) == ACTIVE }
    }

    fun markActive(context: Context, actionId: String, chatId: String) {
        preferences(context).edit()
            .putString(chatIdKey(actionId), chatId)
            .putString(stateKey(actionId), ACTIVE)
            .apply()
    }

    fun markCompletedForChat(context: Context, chatId: String) =
        markTerminalForChat(context, chatId, COMPLETED)

    fun markFailedForChat(context: Context, chatId: String) =
        markTerminalForChat(context, chatId, FAILED)

    private fun markTerminalForChat(context: Context, chatId: String, state: String) {
        val preferences = preferences(context)
        val editor = preferences.edit()
        preferences.all.forEach { (key, value) ->
            if (key.endsWith(".chat_id") && value == chatId) {
                editor.putString(key.removeSuffix(".chat_id") + ".state", state)
            }
        }
        editor.apply()
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private fun chatIdKey(actionId: String) = "action.${actionId.trim()}.chat_id"

    private fun stateKey(actionId: String) = "action.${actionId.trim()}.state"
}
