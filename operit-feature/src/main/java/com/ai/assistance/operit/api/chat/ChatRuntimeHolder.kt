package com.ai.assistance.operit.api.chat

import android.content.Context
import com.ai.assistance.operit.rescue.ui.RescueActivity
import com.ai.assistance.operit.services.ChatServiceCore
import com.ai.assistance.operit.services.core.ChatSelectionMode
import com.ai.assistance.operit.util.AppLogger
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class ChatRuntimeHolder private constructor(context: Context) {
    private val appContext = context.applicationContext
    /**
     * The rescue activity runs in a separate process.  That process must never eagerly create
     * the MAIN/FLOATING cores: doing so initializes the Node/Termux delegate even though rescue is
     * meant to be an Android-local runtime.
     */
    private val isRescueProcess = RescueActivity.isRescueContext(appContext)
    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cores = ConcurrentHashMap<ChatRuntimeSlot, ChatServiceCore>()
    private val _activeConversationCount = MutableStateFlow(0)
    val activeConversationCount: StateFlow<Int> = _activeConversationCount.asStateFlow()
    private val _currentSessionToolCount = MutableStateFlow(0)
    val currentSessionToolCount: StateFlow<Int> = _currentSessionToolCount.asStateFlow()

    init {
        if (isRescueProcess) {
            // Rescue owns only its isolated slot.  The Rust backend itself remains lazy inside
            // RescuePiChatEngine, but the Android ChatServiceCore is needed by the full UI.
            getCore(ChatRuntimeSlot.RESCUE)
            observeStats()
        } else {
            // Keep normal startup limited to the two existing slots. Rescue is created only when
            // the rescue activity is opened.
            arrayOf(ChatRuntimeSlot.MAIN, ChatRuntimeSlot.FLOATING).forEach { slot ->
                getCore(slot)
            }
            setupCrossSessionSync()
            observeStats()
        }
    }

    fun getCore(slot: ChatRuntimeSlot): ChatServiceCore {
        // A stray shared component in the rescue process may still ask for MAIN (for example a
        // generic HTTP bridge). Never let that request instantiate the Node/Termux core there.
        val effectiveSlot = if (isRescueProcess) ChatRuntimeSlot.RESCUE else slot
        return cores.getOrPut(effectiveSlot) {
            ChatServiceCore(
                context = appContext,
                coroutineScope = runtimeScope,
                runtimeSlot = effectiveSlot,
                selectionMode = when (effectiveSlot) {
                    ChatRuntimeSlot.MAIN -> ChatSelectionMode.FOLLOW_GLOBAL
                    ChatRuntimeSlot.FLOATING -> ChatSelectionMode.LOCAL_ONLY
                    ChatRuntimeSlot.RESCUE -> ChatSelectionMode.RESCUE_LOCAL
                }
            )
        }
    }

    private fun observeStats() {
        if (isRescueProcess) {
            val rescueCore = getCore(ChatRuntimeSlot.RESCUE)
            runtimeScope.launch {
                rescueCore.activeStreamingChatIds.collect { activeChatIds ->
                    _activeConversationCount.value = activeChatIds.size
                }
            }
            runtimeScope.launch {
                combine(
                    rescueCore.activeStreamingChatIds,
                    rescueCore.currentTurnToolInvocationCountByChatId
                ) { activeChatIds, counts ->
                    countCurrentTurnToolsForActiveChats(activeChatIds, counts)
                }.collect { count ->
                    _currentSessionToolCount.value = count
                }
            }
            return
        }

        val mainCore = getCore(ChatRuntimeSlot.MAIN)
        val floatingCore = getCore(ChatRuntimeSlot.FLOATING)

        runtimeScope.launch {
            combine(
                mainCore.activeStreamingChatIds,
                floatingCore.activeStreamingChatIds
            ) { mainActiveChatIds, floatingActiveChatIds ->
                (mainActiveChatIds + floatingActiveChatIds).size
            }.collect { count ->
                _activeConversationCount.value = count
            }
        }

        runtimeScope.launch {
            combine(
                mainCore.activeStreamingChatIds,
                mainCore.currentTurnToolInvocationCountByChatId,
                floatingCore.activeStreamingChatIds,
                floatingCore.currentTurnToolInvocationCountByChatId
            ) { mainActiveChatIds, mainCounts, floatingActiveChatIds, floatingCounts ->
                countCurrentTurnToolsForActiveChats(mainActiveChatIds, mainCounts) +
                    countCurrentTurnToolsForActiveChats(floatingActiveChatIds, floatingCounts)
            }.collect { count ->
                _currentSessionToolCount.value = count
            }
        }
    }

    private fun countCurrentTurnToolsForActiveChats(
        activeChatIds: Set<String>,
        countMap: Map<String, Int>
    ): Int {
        return activeChatIds.sumOf { chatId -> countMap[chatId] ?: 0 }
    }

    private fun setupCrossSessionSync() {
        registerChatSelectionSync(
            sourceSlot = ChatRuntimeSlot.MAIN,
            targetSlot = ChatRuntimeSlot.FLOATING
        )
        registerTurnSync(
            sourceSlot = ChatRuntimeSlot.MAIN,
            targetSlot = ChatRuntimeSlot.FLOATING
        )
        registerTurnSync(
            sourceSlot = ChatRuntimeSlot.FLOATING,
            targetSlot = ChatRuntimeSlot.MAIN
        )
    }

    private fun registerTurnSync(
        sourceSlot: ChatRuntimeSlot,
        targetSlot: ChatRuntimeSlot
    ) {
        val sourceCore = getCore(sourceSlot)
        val targetCore = getCore(targetSlot)

        sourceCore.setAdditionalOnTurnComplete { chatId, inputTokens, outputTokens, windowSize ->
            if (chatId.isNullOrBlank()) {
                return@setAdditionalOnTurnComplete
            }
            if (targetCore.currentChatId.value != chatId) {
                return@setAdditionalOnTurnComplete
            }

            runtimeScope.launch {
                try {
                    targetCore.reloadChatMessagesSmart(chatId)
                    targetCore.getTokenStatisticsDelegate()
                        .setTokenCounts(chatId, inputTokens, outputTokens, windowSize)
                    AppLogger.d(
                        TAG,
                        "跨 Session smart 同步完成: $sourceSlot -> $targetSlot, chatId=$chatId, input=$inputTokens, output=$outputTokens, window=$windowSize"
                    )
                } catch (e: Exception) {
                    AppLogger.e(
                        TAG,
                        "跨 Session smart 同步失败: $sourceSlot -> $targetSlot, chatId=$chatId",
                        e
                    )
                }
            }
        }
    }

    fun syncMainChatSelectionToFloating(chatId: String) {
        if (isRescueProcess || chatId.isBlank()) return
        syncChatSelection(
            sourceSlot = ChatRuntimeSlot.MAIN,
            targetSlot = ChatRuntimeSlot.FLOATING,
            chatId = chatId
        )
    }

    private fun registerChatSelectionSync(
        sourceSlot: ChatRuntimeSlot,
        targetSlot: ChatRuntimeSlot
    ) {
        val sourceCore = getCore(sourceSlot)

        runtimeScope.launch {
            sourceCore.currentChatId
                .collect { chatId ->
                    if (chatId.isNullOrBlank()) {
                        return@collect
                    }
                    syncChatSelection(sourceSlot, targetSlot, chatId)
                }
        }
    }

    private fun syncChatSelection(
        sourceSlot: ChatRuntimeSlot,
        targetSlot: ChatRuntimeSlot,
        chatId: String
    ) {
        val targetCore = getCore(targetSlot)
        if (targetCore.currentChatId.value == chatId) {
            return
        }

        try {
            targetCore.switchChatLocal(chatId)
            AppLogger.d(
                TAG,
                "跨 Session 当前聊天同步: $sourceSlot -> $targetSlot, chatId=$chatId"
            )
        } catch (e: Exception) {
            AppLogger.e(
                TAG,
                "跨 Session 当前聊天同步失败: $sourceSlot -> $targetSlot, chatId=$chatId",
                e
            )
        }
    }

    companion object {
        private const val TAG = "ChatRuntimeHolder"

        @Volatile
        private var instance: ChatRuntimeHolder? = null

        fun getInstance(context: Context): ChatRuntimeHolder {
            return instance ?: synchronized(this) {
                instance ?: ChatRuntimeHolder(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}
