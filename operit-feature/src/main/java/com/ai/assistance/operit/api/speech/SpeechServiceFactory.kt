package com.ai.assistance.operit.api.speech

import android.content.Context

/** Compatibility factory for host builds where speech recognition is unavailable. */
object SpeechServiceFactory {
    enum class SpeechServiceType {
        SHERPA_NCNN,
        OPENAI_STT,
        DEEPGRAM_STT,
    }

    private val unavailable = UnavailableSpeechService()

    fun createSpeechService(context: Context): SpeechService = unavailable

    fun createWakeSpeechService(context: Context): SpeechService = unavailable

    fun createSpeechService(context: Context, type: SpeechServiceType): SpeechService = unavailable

    fun getInstance(context: Context): SpeechService = unavailable

    fun resetInstance() = Unit
}
