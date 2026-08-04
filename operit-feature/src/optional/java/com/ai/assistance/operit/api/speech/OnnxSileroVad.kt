package com.ai.assistance.operit.api.speech

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Lightweight energy-based VAD retained under the old class name for preference and call-site
 * compatibility. The ONNX model and runtime are intentionally not bundled.
 */
class OnnxSileroVad(
    context: Context,
    private val sampleRate: Int = 16000,
    private val frameSize: Int = 512,
    private val mode: Mode = Mode.NORMAL,
    speechDurationMs: Int = 50,
    silenceDurationMs: Int = 300,
    modelAssetPath: String = "models/silero_vad.onnx",
) : AutoCloseable {

    private companion object {
        private const val TAG = "OnnxSileroVad"
    }

    enum class Mode {
        OFF,
        NORMAL,
        AGGRESSIVE,
        VERY_AGGRESSIVE,
    }

    private var speechFramesCount = 0
    private var silenceFramesCount = 0
    private var maxSpeechFramesCount = msToFrames(speechDurationMs)
    private var maxSilenceFramesCount = msToFrames(silenceDurationMs)

    init {
        context.applicationContext
        AppLogger.d(TAG, "Using lightweight energy VAD; ONNX asset is disabled: $modelAssetPath")
    }

    fun reset() {
        speechFramesCount = 0
        silenceFramesCount = 0
    }

    fun setSpeechDurationMs(ms: Int) {
        maxSpeechFramesCount = msToFrames(ms)
    }

    fun setSilenceDurationMs(ms: Int) {
        maxSilenceFramesCount = msToFrames(ms)
    }

    fun isSpeech(frame: ShortArray): Boolean {
        if (mode == Mode.OFF) return false
        require(frame.size == frameSize)

        var squareSum = 0.0
        for (sample in frame) {
            val normalized = sample / 32768.0
            squareSum += normalized * normalized
        }
        val rms = sqrt(squareSum / frame.size).toFloat()
        return isContinuousSpeech(rms >= energyThreshold())
    }

    private fun isContinuousSpeech(isSpeechFrame: Boolean): Boolean {
        if (isSpeechFrame) {
            if (speechFramesCount <= maxSpeechFramesCount) speechFramesCount++
            if (speechFramesCount > maxSpeechFramesCount) {
                silenceFramesCount = 0
                return true
            }
        } else {
            if (silenceFramesCount <= maxSilenceFramesCount) silenceFramesCount++
            if (silenceFramesCount > maxSilenceFramesCount) {
                speechFramesCount = 0
                return false
            }
            if (speechFramesCount > maxSpeechFramesCount) return true
        }
        return false
    }

    private fun energyThreshold(): Float =
        when (mode) {
            Mode.NORMAL -> 0.012f
            Mode.AGGRESSIVE -> 0.025f
            Mode.VERY_AGGRESSIVE -> 0.05f
            Mode.OFF -> Float.POSITIVE_INFINITY
        }

    private fun msToFrames(ms: Int): Int {
        if (ms <= 0) return 0
        val frameDurationMs = (frameSize * 1000.0) / sampleRate
        return ceil(ms / frameDurationMs).toInt().coerceAtLeast(0)
    }

    override fun close() = Unit
}
