package com.ai.assistance.operit.api.speech

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Compatibility implementation for removed on-device speech engines. */
class UnavailableSpeechService(
    private val message: String = "Local speech recognition is not included in this build",
) : SpeechService {
    private val initialized = MutableStateFlow(false)
    private val state = MutableStateFlow(SpeechService.RecognitionState.UNINITIALIZED)
    private val result = MutableStateFlow(SpeechService.RecognitionResult(""))
    private val error = MutableStateFlow(SpeechService.RecognitionError(0, ""))
    private val volume = MutableStateFlow(0f)

    override val isInitialized: StateFlow<Boolean> = initialized.asStateFlow()
    override val isRecognizing: Boolean get() = false
    override val currentState: SpeechService.RecognitionState get() = state.value
    override val recognitionStateFlow: StateFlow<SpeechService.RecognitionState> = state.asStateFlow()
    override val recognitionResultFlow: StateFlow<SpeechService.RecognitionResult> = result.asStateFlow()
    override val recognitionErrorFlow: StateFlow<SpeechService.RecognitionError> = error.asStateFlow()
    override val volumeLevelFlow: StateFlow<Float> = volume.asStateFlow()

    override suspend fun initialize(): Boolean = fail()
    override suspend fun startRecognition(
        languageCode: String,
        continuousMode: Boolean,
        partialResults: Boolean,
        audioSource: Int,
    ): Boolean = fail()

    override suspend fun stopRecognition(): Boolean = false
    override suspend fun cancelRecognition() = Unit
    override fun shutdown() = Unit
    override suspend fun getSupportedLanguages(): List<String> = emptyList()
    override suspend fun recognize(audioData: FloatArray) {
        fail()
    }

    private fun fail(): Boolean {
        initialized.value = false
        state.value = SpeechService.RecognitionState.ERROR
        error.value = SpeechService.RecognitionError(-1, message)
        return false
    }
}
