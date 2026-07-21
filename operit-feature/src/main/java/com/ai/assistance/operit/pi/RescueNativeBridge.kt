package com.ai.assistance.operit.pi

/** JNI surface for the Android-resident rescue agent. The library loads only on first access. */
object RescueNativeBridge {
    init {
        System.loadLibrary("wuxianpi_rescue")
    }

    external fun nativeOpenSession(configJson: String): String

    external fun nativePrompt(chatId: String, prompt: String, requestId: String): String

    external fun nativeCancel(chatId: String): Boolean

    external fun nativeCompleteHostTool(requestId: String, resultJson: String): Boolean

    external fun nativeCompact(chatId: String, instructions: String, requestId: String): String

    external fun nativePollEvents(maxEvents: Int): String

    external fun nativeCloseSession(chatId: String): Boolean
}
