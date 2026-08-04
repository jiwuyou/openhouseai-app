package com.ai.assistance.operit.util

/** Compatibility facade retained for callers that can operate without FFmpeg. */
object FFmpegUtil {
    private const val TAG = "FFmpegUtil"

    fun scaleFilterMaxWidth(maxWidth: Int): String = "scale=min(${maxWidth}\\,iw):-2"

    fun executeCommand(command: String): Boolean {
        AppLogger.w(TAG, "FFmpeg is not included in this build; command skipped: $command")
        return false
    }

    fun getMediaInfo(filePath: String): MediaInformation? {
        AppLogger.d(TAG, "FFprobe is not included; basic file metadata will be used for $filePath")
        return null
    }

    data class MediaInformation(
        val format: String? = null,
        val duration: String? = null,
        val bitrate: String? = null,
        val streams: List<MediaStream>? = null,
    )

    data class MediaStream(val type: String? = null)
}
