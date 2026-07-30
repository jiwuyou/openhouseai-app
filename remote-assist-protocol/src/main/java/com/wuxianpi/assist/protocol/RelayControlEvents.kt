package com.wuxianpi.assist.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val RELAY_CONTROL_VERSION: Int = 1

@Serializable
enum class RelayPeerState {
    @SerialName("waiting")
    WAITING,

    @SerialName("connected")
    CONNECTED,
}

@Serializable
sealed interface RelayControlEvent {
    val relay: Int
}

@Serializable
@SerialName("peer_status")
data class RelayPeerStatus(
    val status: RelayPeerState,
    val role: Role? = null,
    override val relay: Int = RELAY_CONTROL_VERSION,
) : RelayControlEvent {
    init {
        requireRelayControlVersion(relay)
        when (status) {
            RelayPeerState.WAITING -> if (role != null) {
                throw AssistProtocolException("Waiting peer_status cannot include role")
            }

            RelayPeerState.CONNECTED -> if (role == null) {
                throw AssistProtocolException("Connected peer_status requires role")
            }
        }
    }
}

@Serializable
@SerialName("peer_left")
data class RelayPeerLeft(
    val role: Role,
    override val relay: Int = RELAY_CONTROL_VERSION,
) : RelayControlEvent {
    init {
        requireRelayControlVersion(relay)
    }
}

sealed interface RelayTextFrame {
    data class Application(val envelope: RelayEnvelope) : RelayTextFrame

    data class Control(val event: RelayControlEvent) : RelayTextFrame

    data class Handshake(val message: HandshakeMessage) : RelayTextFrame
}

internal fun requireRelayControlVersion(version: Int) {
    if (version != RELAY_CONTROL_VERSION) {
        throw AssistProtocolException("Unsupported relay control version: $version")
    }
}
