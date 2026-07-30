package com.wuxianpi.assist.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

const val HANDSHAKE_MARKER_VERSION: Int = 1

@Serializable
sealed interface HandshakeMessage {
    val version: Int
    val handshake: Int
}

@Serializable
@SerialName("host_hello")
data class HostHello(
    val roomId: String,
    val inviteNonce: String,
    val identity: IdentityPublicKey,
    val ephemeralPublicKey: EphemeralPublicKey,
    val nonce: String,
    val signature: String,
    override val version: Int = ASSIST_PROTOCOL_VERSION,
    override val handshake: Int = HANDSHAKE_MARKER_VERSION,
) : HandshakeMessage {
    init {
        validateHelloFields(roomId, inviteNonce, nonce, signature, version, handshake)
    }
}

@Serializable
@SerialName("assist_hello")
data class AssistHello(
    val roomId: String,
    val inviteNonce: String,
    val identity: IdentityPublicKey,
    val ephemeralPublicKey: EphemeralPublicKey,
    val nonce: String,
    val hostHelloHash: String,
    val signature: String,
    override val version: Int = ASSIST_PROTOCOL_VERSION,
    override val handshake: Int = HANDSHAKE_MARKER_VERSION,
) : HandshakeMessage {
    init {
        validateHelloFields(roomId, inviteNonce, nonce, signature, version, handshake)
        decodeCanonicalBase64Url(hostHelloHash, TRANSCRIPT_HASH_BYTES, "host hello hash")
    }
}

@Serializable
@SerialName("verification_required")
data class VerificationRequired(
    val roomId: String,
    val transcriptHash: String,
    val sasDigits: Int,
    val peerIdentityFingerprint: String,
    val signature: String,
    override val version: Int = ASSIST_PROTOCOL_VERSION,
    override val handshake: Int = HANDSHAKE_MARKER_VERSION,
) : HandshakeMessage {
    init {
        validateVerificationFields(
            roomId,
            transcriptHash,
            peerIdentityFingerprint,
            signature,
            version,
            handshake,
        )
        if (sasDigits !in setOf(6, 8)) {
            throw AssistProtocolException("SAS must contain 6 or 8 digits")
        }
    }
}

@Serializable
@SerialName("verification_result")
data class VerificationResult(
    val roomId: String,
    val transcriptHash: String,
    val accepted: Boolean,
    val peerIdentityFingerprint: String,
    val reason: String? = null,
    val signature: String,
    override val version: Int = ASSIST_PROTOCOL_VERSION,
    override val handshake: Int = HANDSHAKE_MARKER_VERSION,
) : HandshakeMessage {
    init {
        validateVerificationFields(
            roomId = roomId,
            transcriptHash = transcriptHash,
            peerIdentityFingerprint = peerIdentityFingerprint,
            signature = signature,
            version = version,
            handshake = handshake,
        )
        if (accepted && reason != null) {
            throw AssistProtocolException("Accepted verification result cannot include reason")
        }
        if (!accepted && reason.isNullOrBlank()) {
            throw AssistProtocolException("Rejected verification result requires reason")
        }
    }
}

internal fun hostHelloSigningPayload(hello: HostHello): ByteArray = canonicalBytes("host_hello") {
    writeProtocolHeader(hello.version, hello.handshake)
    writeString(hello.roomId)
    writeString(hello.inviteNonce)
    writeIdentity(hello.identity)
    writeEphemeral(hello.ephemeralPublicKey)
    writeString(hello.nonce)
}

internal fun assistHelloSigningPayload(hello: AssistHello): ByteArray = canonicalBytes("assist_hello") {
    writeProtocolHeader(hello.version, hello.handshake)
    writeString(hello.roomId)
    writeString(hello.inviteNonce)
    writeIdentity(hello.identity)
    writeEphemeral(hello.ephemeralPublicKey)
    writeString(hello.nonce)
    writeString(hello.hostHelloHash)
}

internal fun verificationRequiredSigningPayload(message: VerificationRequired): ByteArray =
    canonicalBytes("verification_required") {
        writeProtocolHeader(message.version, message.handshake)
        writeString(message.roomId)
        writeString(message.transcriptHash)
        writeInt(message.sasDigits)
        writeString(message.peerIdentityFingerprint)
    }

internal fun verificationResultSigningPayload(message: VerificationResult): ByteArray =
    canonicalBytes("verification_result") {
        writeProtocolHeader(message.version, message.handshake)
        writeString(message.roomId)
        writeString(message.transcriptHash)
        writeBoolean(message.accepted)
        writeString(message.peerIdentityFingerprint)
        writeNullableString(message.reason)
    }

internal fun hostHelloHash(hello: HostHello): ByteArray = sha256(
    canonicalBytes("host_hello_complete") {
        write(hostHelloSigningPayload(hello))
        writeString(hello.signature)
    },
)

internal fun handshakeTranscriptHash(hostHello: HostHello, assistHello: AssistHello): ByteArray = sha256(
    canonicalBytes("full_handshake_transcript") {
        write(hostHelloSigningPayload(hostHello))
        writeString(hostHello.signature)
        write(assistHelloSigningPayload(assistHello))
        writeString(assistHello.signature)
    },
)

private fun validateHelloFields(
    roomId: String,
    inviteNonce: String,
    nonce: String,
    signature: String,
    version: Int,
    handshake: Int,
) {
    requireVersion(version)
    requireHandshakeVersion(handshake)
    validateRoomId(roomId)
    decodeCanonicalBase64Url(inviteNonce, HANDSHAKE_NONCE_BYTES, "invite nonce")
    decodeCanonicalBase64Url(nonce, HANDSHAKE_NONCE_BYTES, "hello nonce")
    requireSignature(signature)
}

private fun validateVerificationFields(
    roomId: String,
    transcriptHash: String,
    peerIdentityFingerprint: String,
    signature: String,
    version: Int,
    handshake: Int,
) {
    requireVersion(version)
    requireHandshakeVersion(handshake)
    validateRoomId(roomId)
    decodeCanonicalBase64Url(transcriptHash, TRANSCRIPT_HASH_BYTES, "transcript hash")
    decodeCanonicalBase64Url(
        peerIdentityFingerprint,
        TRANSCRIPT_HASH_BYTES,
        "peer identity fingerprint",
    )
    requireSignature(signature)
}

private fun requireSignature(value: String) {
    if (decodeCanonicalBase64Url(value, "signature").size < MIN_ECDSA_SIGNATURE_BYTES) {
        throw AssistProtocolException("Handshake signature is too short")
    }
}

private fun requireHandshakeVersion(version: Int) {
    if (version != HANDSHAKE_MARKER_VERSION) {
        throw AssistProtocolException("Unsupported handshake marker version: $version")
    }
}

private inline fun canonicalBytes(label: String, block: DataOutputStream.() -> Unit): ByteArray {
    val output = ByteArrayOutputStream()
    DataOutputStream(output).use { data ->
        data.writeString("wuxianpi-assist/$label")
        data.block()
    }
    return output.toByteArray()
}

private fun DataOutputStream.writeProtocolHeader(version: Int, handshake: Int) {
    writeInt(version)
    writeInt(handshake)
}

private fun DataOutputStream.writeIdentity(identity: IdentityPublicKey) {
    writeString(identity.algorithm.name)
    writeString(identity.encodedKey)
    writeString(identity.fingerprint)
}

private fun DataOutputStream.writeEphemeral(ephemeral: EphemeralPublicKey) {
    writeString(ephemeral.algorithm.name)
    writeString(ephemeral.encodedKey)
}

private fun DataOutputStream.writeString(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    writeInt(bytes.size)
    write(bytes)
}

private fun DataOutputStream.writeNullableString(value: String?) {
    writeBoolean(value != null)
    if (value != null) writeString(value)
}

private fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)

internal const val TRANSCRIPT_HASH_BYTES = 32
private const val MIN_ECDSA_SIGNATURE_BYTES = 64
