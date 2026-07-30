package com.wuxianpi.assist.protocol

import kotlinx.serialization.Serializable

@Serializable
data class EncryptedFrame(
    val version: Int = ASSIST_PROTOCOL_VERSION,
    val direction: Direction,
    val sequence: Long,
    val ciphertext: String,
) {
    init {
        requireVersion(version)
        if (sequence <= 0) {
            throw AssistProtocolException("Encrypted frame sequence must be positive")
        }
        if (decodeCanonicalBase64Url(ciphertext, "ciphertext").size < MIN_TINK_AES_GCM_OUTPUT_BYTES) {
            throw AssistProtocolException("Encrypted frame ciphertext is too short")
        }
    }
}

@Serializable
data class RelayEnvelope(
    val version: Int = ASSIST_PROTOCOL_VERSION,
    val roomId: String,
    val role: Role,
    val frame: EncryptedFrame,
) {
    init {
        requireVersion(version)
        validateRoomId(roomId)
        if (frame.version != version) {
            throw AssistProtocolException("Envelope and frame versions do not match")
        }
        if (frame.direction != role.outboundDirection) {
            throw AssistProtocolException("Envelope role does not match frame direction")
        }
    }
}

// Tink AES-GCM output contains a 12-byte nonce, ciphertext and a 16-byte tag.
private const val MIN_TINK_AES_GCM_OUTPUT_BYTES = 29
