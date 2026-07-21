package com.ai.assistance.operit.api.chat

enum class ChatRuntimeSlot {
    MAIN,
    FLOATING,
    /**
     * The Android-local rescue assistant.  This slot is deliberately separate from MAIN so
     * rescue sessions and their lifecycle never reuse the Termux/Node runtime bindings.
     */
    RESCUE
}
