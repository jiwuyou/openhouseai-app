package com.wuxianpi.assist.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AssistCryptoTest {
    @Test
    fun authorizedHostAndAssistEncryptAndDecryptInBothDirections() {
        val handshake = completeHandshake()
        val hostMessage = Ping("host-ping", 1)
        val assistMessage = Pong("assist-pong", 2)

        assertEquals(
            hostMessage,
            handshake.assistSession.decryptMessage(handshake.hostSession.encryptMessage(hostMessage)),
        )
        assertEquals(
            assistMessage,
            handshake.hostSession.decryptMessage(handshake.assistSession.encryptMessage(assistMessage)),
        )
    }

    @Test
    fun decryptorRejectsReplayWithoutAdvancingPastExpectedSequence() {
        val handshake = completeHandshake(110)
        val first = handshake.hostSession.encryptPayload("first".encodeToByteArray())
        val second = handshake.hostSession.encryptPayload("second".encodeToByteArray())

        assertArrayEquals("first".encodeToByteArray(), handshake.assistSession.decryptPayload(first))
        assertThrows(AssistProtocolException::class.java) {
            handshake.assistSession.decryptPayload(first)
        }
        assertArrayEquals("second".encodeToByteArray(), handshake.assistSession.decryptPayload(second))
    }

    @Test
    fun decryptorRejectsSequenceGapsAndStillAcceptsExpectedFrame() {
        val handshake = completeHandshake(120)
        val first = handshake.hostSession.encryptPayload("first".encodeToByteArray())
        val second = handshake.hostSession.encryptPayload("second".encodeToByteArray())

        assertThrows(AssistProtocolException::class.java) {
            handshake.assistSession.decryptPayload(second)
        }
        assertArrayEquals("first".encodeToByteArray(), handshake.assistSession.decryptPayload(first))
        assertArrayEquals("second".encodeToByteArray(), handshake.assistSession.decryptPayload(second))
    }

    @Test
    fun tamperedCiphertextFailsAuthenticationAndDoesNotAdvanceSequence() {
        val handshake = completeHandshake(130)
        val original = handshake.hostSession.encryptPayload("protected".encodeToByteArray())
        val bytes = decodeBase64Url(original.ciphertext, "ciphertext")
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        val tampered = original.copy(ciphertext = encodeBase64Url(bytes))

        assertThrows(AssistProtocolException::class.java) {
            handshake.assistSession.decryptPayload(tampered)
        }
        assertArrayEquals(
            "protected".encodeToByteArray(),
            handshake.assistSession.decryptPayload(original),
        )
    }

    @Test
    fun keyMaterialFromAnotherHandshakeFailsAuthentication() {
        val sender = completeHandshake(140)
        val unrelatedReceiver = completeHandshake(150)

        assertThrows(AssistProtocolException::class.java) {
            unrelatedReceiver.assistSession.decryptPayload(
                sender.hostSession.encryptPayload("secret".encodeToByteArray()),
            )
        }
    }

    @Test
    fun wrongDirectionIsRejectedBeforeDecryption() {
        val handshake = completeHandshake(160)
        val frame = handshake.hostSession.encryptPayload("direction".encodeToByteArray())
            .copy(direction = Direction.ASSIST_TO_HOST)

        assertThrows(AssistProtocolException::class.java) {
            handshake.assistSession.decryptPayload(frame)
        }
    }

    @Test
    fun alteredSequenceIsBoundByAuthenticationData() {
        val handshake = completeHandshake(170)
        val first = handshake.hostSession.encryptPayload("one".encodeToByteArray())
        val second = handshake.hostSession.encryptPayload("two".encodeToByteArray())
        val ciphertextFromSecondAtFirstSequence = second.copy(sequence = first.sequence)

        assertThrows(AssistProtocolException::class.java) {
            handshake.assistSession.decryptPayload(ciphertextFromSecondAtFirstSequence)
        }
        assertArrayEquals("one".encodeToByteArray(), handshake.assistSession.decryptPayload(first))
    }
}
