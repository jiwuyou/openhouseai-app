package com.wuxianpi.assist.protocol

import com.google.crypto.tink.Aead
import com.google.crypto.tink.subtle.AesGcmJce
import com.google.crypto.tink.subtle.Hkdf
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException

class AuthorizedAssistCryptoSession internal constructor(
    val roomId: String,
    val localRole: Role,
    val peerIdentityFingerprint: String,
    sharedSecret: ByteArray,
    transcriptHash: ByteArray,
) {
    val isAuthorized: Boolean = true

    private val encryptor = StatefulEncryptor(
        roomId = roomId,
        direction = localRole.outboundDirection,
        aead = createSessionAead(sharedSecret, transcriptHash, localRole.outboundDirection),
    )
    private val decryptor = StatefulDecryptor(
        roomId = roomId,
        direction = localRole.inboundDirection,
        aead = createSessionAead(sharedSecret, transcriptHash, localRole.inboundDirection),
    )

    init {
        validateRoomId(roomId)
        decodeCanonicalBase64Url(
            peerIdentityFingerprint,
            TRANSCRIPT_HASH_BYTES,
            "peer identity fingerprint",
        )
        if (transcriptHash.size != TRANSCRIPT_HASH_BYTES) {
            throw AssistProtocolException("Handshake transcript hash must contain 32 bytes")
        }
    }

    fun encryptPayload(plaintext: ByteArray): EncryptedFrame = encryptor.encrypt(plaintext)

    fun decryptPayload(frame: EncryptedFrame): ByteArray = decryptor.decrypt(frame)

    fun encryptMessage(message: AssistMessage): EncryptedFrame = encryptor.encryptMessage(message)

    fun decryptMessage(frame: EncryptedFrame): AssistMessage = decryptor.decryptMessage(frame)
}

internal class StatefulEncryptor(
    private val roomId: String,
    val direction: Direction,
    private val aead: Aead,
    lastSequence: Long = 0,
) {
    private var nextSequence = checkedNextSequence(lastSequence)

    @Synchronized
    fun encrypt(plaintext: ByteArray): EncryptedFrame {
        val sequence = nextSequence
        val ciphertext = try {
            aead.encrypt(plaintext, associatedData(roomId, direction, sequence))
        } catch (error: GeneralSecurityException) {
            throw AssistProtocolException("Unable to encrypt assist frame", error)
        }
        nextSequence = checkedNextSequence(sequence)
        return EncryptedFrame(
            direction = direction,
            sequence = sequence,
            ciphertext = encodeBase64Url(ciphertext),
        )
    }

    fun encryptMessage(message: AssistMessage): EncryptedFrame =
        encrypt(AssistProtocolJson.encodeMessage(message).toByteArray(StandardCharsets.UTF_8))
}

internal class StatefulDecryptor(
    private val roomId: String,
    val direction: Direction,
    private val aead: Aead,
    lastSequence: Long = 0,
) {
    private var expectedSequence = checkedNextSequence(lastSequence)

    @Synchronized
    fun decrypt(frame: EncryptedFrame): ByteArray {
        if (frame.direction != direction) {
            throw AssistProtocolException(
                "Unexpected frame direction: expected $direction, received ${frame.direction}",
            )
        }
        if (frame.sequence != expectedSequence) {
            throw AssistProtocolException(
                "Unexpected frame sequence: expected $expectedSequence, received ${frame.sequence}",
            )
        }
        val plaintext = try {
            aead.decrypt(
                decodeBase64Url(frame.ciphertext, "ciphertext"),
                associatedData(roomId, direction, frame.sequence),
            )
        } catch (error: GeneralSecurityException) {
            throw AssistProtocolException("Assist frame authentication failed", error)
        }
        expectedSequence = checkedNextSequence(frame.sequence)
        return plaintext
    }

    fun decryptMessage(frame: EncryptedFrame): AssistMessage =
        AssistProtocolJson.decodeMessage(decrypt(frame).toString(StandardCharsets.UTF_8))
}

internal fun deriveSasCode(
    sharedSecret: ByteArray,
    transcriptHash: ByteArray,
    digits: Int,
): String {
    if (digits !in setOf(6, 8)) {
        throw AssistProtocolException("SAS must contain 6 or 8 digits")
    }
    val bytes = deriveKey(sharedSecret, transcriptHash, "sas", SAS_DERIVED_BYTES)
    return try {
        val modulus = BigInteger.TEN.pow(digits)
        BigInteger(1, bytes).mod(modulus).toString().padStart(digits, '0')
    } finally {
        bytes.fill(0)
    }
}

private fun createSessionAead(
    sharedSecret: ByteArray,
    transcriptHash: ByteArray,
    direction: Direction,
): Aead {
    val key = deriveKey(sharedSecret, transcriptHash, "session/${direction.name}", AES_256_KEY_BYTES)
    return try {
        AesGcmJce(key)
    } catch (error: GeneralSecurityException) {
        throw AssistProtocolException("Unable to initialize AES-256-GCM", error)
    } finally {
        key.fill(0)
    }
}

private fun deriveKey(
    sharedSecret: ByteArray,
    transcriptHash: ByteArray,
    purpose: String,
    size: Int,
): ByteArray {
    if (sharedSecret.isEmpty() || transcriptHash.size != TRANSCRIPT_HASH_BYTES) {
        throw AssistProtocolException("Invalid handshake key material")
    }
    val info = "wuxianpi-assist/v1/$purpose".toByteArray(StandardCharsets.UTF_8)
    return try {
        Hkdf.computeHkdf("HMACSHA256", sharedSecret, transcriptHash, info, size)
    } catch (error: GeneralSecurityException) {
        throw AssistProtocolException("Unable to derive assist session key", error)
    }
}

private fun associatedData(roomId: String, direction: Direction, sequence: Long): ByteArray =
    buildString {
        append("wuxianpi-assist")
        append('\n')
        append(ASSIST_PROTOCOL_VERSION)
        append('\n')
        append(roomId)
        append('\n')
        append(direction.name)
        append('\n')
        append(sequence)
    }.toByteArray(StandardCharsets.UTF_8)

private fun checkedNextSequence(current: Long): Long {
    if (current < 0 || current == Long.MAX_VALUE) {
        throw AssistProtocolException("Assist sequence is exhausted or invalid")
    }
    return current + 1
}

private const val AES_256_KEY_BYTES = 32
private const val SAS_DERIVED_BYTES = 16
