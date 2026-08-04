package com.ai.assistance.operit.api.voice

import android.content.Context

/** Compatibility factory for host builds where text-to-speech is unavailable. */
object VoiceServiceFactory {
    enum class VoiceServiceType {
        SIMPLE_TTS,
        HTTP_TTS,
        OPENAI_WS_TTS,
        SILICONFLOW_TTS,
        MINIMAX_TTS,
        MIMO_TTS,
        DOUBAO_TTS,
        OPENAI_TTS,
        VITS_TTS,
    }

    private val unavailable = UnavailableVoiceService()

    fun createVoiceService(context: Context): VoiceService = unavailable

    fun getInstance(context: Context): VoiceService = unavailable

    fun resetInstance() = Unit
}
