package com.ai.assistance.operit.services.core

enum class ChatSelectionMode {
    FOLLOW_GLOBAL,
    LOCAL_ONLY,
    /** Local-only selection whose history is restricted to the Rescue AI namespace. */
    RESCUE_LOCAL
}
