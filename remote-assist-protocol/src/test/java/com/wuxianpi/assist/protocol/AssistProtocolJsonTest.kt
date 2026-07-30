package com.wuxianpi.assist.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AssistProtocolJsonTest {
    @Test
    fun everyApplicationMessageRoundTrips() {
        val message = ConversationMessage(
            id = "message-1",
            role = ConversationRole.ASSISTANT,
            content = "diagnostic output",
            timestampMs = 10,
        )
        val messages: List<AssistMessage> = listOf(
            JoinRequest(
                requestId = "join-1",
                peerId = "peer-1",
                displayName = "Technician",
                requestedPermission = Permission.COLLABORATE,
                timestampMs = 1,
            ),
            JoinDecision(
                requestId = "join-1",
                accepted = true,
                grantedPermission = Permission.COLLABORATE,
                hostDisplayName = "Phone",
                timestampMs = 2,
            ),
            ConversationSnapshot(
                chatId = "chat-1",
                messages = listOf(message),
                generatedAtMs = 11,
            ),
            MessageUpsert(chatId = "chat-1", message = message),
            TurnState(
                chatId = "chat-1",
                state = TurnStatus.EXECUTING_TOOL,
                detail = "termux_exec_command",
                timestampMs = 12,
            ),
            ToolStarted(
                chatId = "chat-1",
                toolCallId = "tool-1",
                toolName = "termux_exec_command",
                argumentsJson = "{\"command\":\"pwd\"}",
                timestampMs = 13,
            ),
            ToolFinished(
                chatId = "chat-1",
                toolCallId = "tool-1",
                toolName = "termux_exec_command",
                content = "/data/data/com.termux/files/home",
                isError = false,
                timestampMs = 14,
            ),
            RemoteUserMessage(
                requestId = "remote-1",
                chatId = "chat-1",
                text = "Check service-manager",
                timestampMs = 15,
            ),
            RemoteUserMessageAck(
                requestId = "remote-1",
                accepted = true,
                messageId = "message-2",
                timestampMs = 16,
            ),
            PeerLeft(reason = "network closed", timestampMs = 17),
            EndSession(reason = "host ended sharing", timestampMs = 18),
            Ping(nonce = "ping-1", timestampMs = 19),
            Pong(nonce = "ping-1", timestampMs = 20),
        )

        messages.forEach { original ->
            assertEquals(original, AssistProtocolJson.decodeMessage(AssistProtocolJson.encodeMessage(original)))
        }
    }

    @Test
    fun encryptedFrameAndRelayEnvelopeRoundTrip() {
        val invite = Invite.create(
            "wss://assist.example.test/relay",
            IdentityKeys.generate(seededRandom(60)).identity,
        )
        val frame = EncryptedFrame(
            direction = Direction.HOST_TO_ASSIST,
            sequence = 1,
            ciphertext = encodeBase64Url(ByteArray(29) { it.toByte() }),
        )
        val envelope = RelayEnvelope(
            roomId = invite.roomId,
            role = Role.HOST,
            frame = frame,
        )

        assertEquals(frame, AssistProtocolJson.decodeFrame(AssistProtocolJson.encodeFrame(frame)))
        assertEquals(
            envelope,
            AssistProtocolJson.decodeRelayEnvelope(AssistProtocolJson.encodeRelayEnvelope(envelope)),
        )
    }

    @Test
    fun relayControlEventsRoundTripAndRejectInvalidShapes() {
        val events: List<RelayControlEvent> = listOf(
            RelayPeerStatus(RelayPeerState.WAITING),
            RelayPeerStatus(RelayPeerState.CONNECTED, Role.ASSIST),
            RelayPeerLeft(Role.HOST),
        )
        events.forEach { original ->
            assertEquals(
                original,
                AssistProtocolJson.decodeRelayControlEvent(
                    AssistProtocolJson.encodeRelayControlEvent(original),
                ),
            )
        }

        assertThrows(AssistProtocolException::class.java) {
            AssistProtocolJson.decodeRelayControlEvent(
                "{\"relay\":1,\"type\":\"peer_status\",\"status\":\"waiting\",\"role\":\"HOST\"}",
            )
        }
        assertThrows(AssistProtocolException::class.java) {
            AssistProtocolJson.decodeRelayControlEvent(
                "{\"relay\":1,\"type\":\"peer_status\",\"status\":\"connected\"}",
            )
        }
        assertThrows(AssistProtocolException::class.java) {
            AssistProtocolJson.decodeRelayControlEvent(
                "{\"relay\":1,\"type\":\"future_control\"}",
            )
        }
        assertThrows(AssistProtocolException::class.java) {
            AssistProtocolJson.decodeRelayControlEvent(
                "{\"relay\":1,\"type\":\"peer_left\",\"role\":\"HOST\",\"unknown\":true}",
            )
        }
    }

    @Test
    fun codecRejectsUnknownMissingAndUnsupportedFields() {
        val valid = AssistProtocolJson.encodeMessage(Ping("nonce", 1))

        assertThrows(AssistProtocolException::class.java) {
            AssistProtocolJson.decodeMessage(valid.dropLast(1) + ",\"unknown\":true}")
        }
        assertThrows(AssistProtocolException::class.java) {
            AssistProtocolJson.decodeMessage(
                "{\"type\":\"ping\",\"nonce\":\"nonce\",\"timestampMs\":1}",
            )
        }
        assertThrows(AssistProtocolException::class.java) {
            AssistProtocolJson.decodeMessage(valid.replace("\"version\":1", "\"version\":2"))
        }
        assertThrows(AssistProtocolException::class.java) {
            AssistProtocolJson.decodeMessage(valid.replace("\"type\":\"ping\"", "\"type\":\"future\""))
        }
        assertThrows(AssistProtocolException::class.java) {
            AssistProtocolJson.decodeMessage(
                "{\"type\":\"ping\",\"timestampMs\":1,\"version\":1}",
            )
        }
    }

    @Test
    fun semanticValidationRejectsInvalidPermissionDecisions() {
        assertThrows(AssistProtocolException::class.java) {
            JoinDecision(
                requestId = "join-1",
                accepted = true,
                grantedPermission = null,
                timestampMs = 1,
            )
        }
        assertThrows(AssistProtocolException::class.java) {
            RemoteUserMessageAck(
                requestId = "message-1",
                accepted = false,
                reason = null,
                timestampMs = 1,
            )
        }
    }
}
