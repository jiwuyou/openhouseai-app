package com.ai.assistance.operit.rescue.remote

import com.wuxianpi.assist.protocol.Permission

enum class RescueAssistHostPhase {
    IDLE,
    CONNECTING,
    WAITING_FOR_PEER,
    WAITING_FOR_SAS,
    AUTHORIZED,
    ERROR,
}

data class RescueAssistHostState(
    val phase: RescueAssistHostPhase = RescueAssistHostPhase.IDLE,
    val inviteUri: String? = null,
    val pinnedChatId: String? = null,
    val peerFingerprint: String? = null,
    val peerDisplayName: String? = null,
    val offeredPermission: Permission? = null,
    val grantedPermission: Permission? = null,
    val error: String? = null,
) {
    val isSharing: Boolean
        get() = phase != RescueAssistHostPhase.IDLE && phase != RescueAssistHostPhase.ERROR
}
