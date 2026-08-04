package com.ai.assistance.operit.api.voice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Non-throwing replacement for voice playback in BASIC and RESCUE hosts. */
class UnavailableVoiceService : VoiceService {
    private val speaking = MutableStateFlow(false)

    override val isInitialized: Boolean = false
    override val isSpeaking: Boolean = false
    override val speakingStateFlow: Flow<Boolean> = speaking

    override suspend fun initialize(): Boolean = false

    override suspend fun speak(
        text: String,
        interrupt: Boolean,
        rate: Float?,
        pitch: Float?,
        extraParams: Map<String, String>,
    ): Boolean = false

    override suspend fun stop(): Boolean = false
    override suspend fun pause(): Boolean = false
    override suspend fun resume(): Boolean = false
    override fun shutdown() = Unit
    override suspend fun getAvailableVoices(): List<VoiceService.Voice> = emptyList()
    override suspend fun setVoice(voiceId: String): Boolean = false
}
