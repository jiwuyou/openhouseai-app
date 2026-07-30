package com.wuxianpi.assist.protocol

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom

class HostHandshake private constructor(
    private val invite: Invite,
    private val identitySigner: IdentitySigner,
    private val ephemeral: EphemeralKeyAgreement,
    private val sasDigits: Int,
    val hostHello: HostHello,
) {
    private var challenge: HostVerificationChallenge? = null

    @Synchronized
    fun receiveAssistHello(message: AssistHello): HostVerificationChallenge {
        if (challenge != null) {
            throw AssistProtocolException("AssistHello has already been processed")
        }
        requireHelloMatchesInvite(message.roomId, message.inviteNonce, invite)
        val expectedHostHash = hostHelloHash(hostHello)
        requireEqualBytes(
            expectedHostHash,
            decodeCanonicalBase64Url(message.hostHelloHash, TRANSCRIPT_HASH_BYTES, "host hello hash"),
            "AssistHello does not bind the current HostHello",
        )
        requireValidSignature(
            message.identity,
            assistHelloSigningPayload(message),
            message.signature,
            "AssistHello signature is invalid",
        )

        val transcriptHash = handshakeTranscriptHash(hostHello, message)
        val sharedSecret = ephemeral.sharedSecret(message.ephemeralPublicKey)
        val sasCode = deriveSasCode(sharedSecret, transcriptHash, sasDigits)
        val unsignedRequired = VerificationRequired(
            roomId = invite.roomId,
            transcriptHash = encodeBase64Url(transcriptHash),
            sasDigits = sasDigits,
            peerIdentityFingerprint = message.identity.fingerprint,
            signature = SIGNATURE_PLACEHOLDER,
        )
        val required = unsignedRequired.copy(
            signature = sign(identitySigner, verificationRequiredSigningPayload(unsignedRequired)),
        )
        return HostVerificationChallenge(
            roomId = invite.roomId,
            signer = identitySigner,
            peerIdentity = message.identity,
            sharedSecret = sharedSecret,
            transcriptHash = transcriptHash,
            expectedSas = sasCode,
            verificationRequired = required,
        ).also { challenge = it }
    }

    companion object {
        fun create(
            invite: Invite,
            identitySigner: IdentitySigner,
            sasDigits: Int = 6,
            secureRandom: SecureRandom = SecureRandom(),
        ): HostHandshake {
            if (identitySigner.identity != invite.hostIdentity) {
                throw AssistProtocolException("Host signer does not match invite identity")
            }
            requireSasDigits(sasDigits)
            val ephemeral = EphemeralKeyAgreement.generate(secureRandom)
            val unsignedHello = HostHello(
                roomId = invite.roomId,
                inviteNonce = invite.inviteNonce,
                identity = identitySigner.identity,
                ephemeralPublicKey = ephemeral.publicKey,
                nonce = randomNonce(secureRandom),
                signature = SIGNATURE_PLACEHOLDER,
            )
            val hostHello = unsignedHello.copy(
                signature = sign(identitySigner, hostHelloSigningPayload(unsignedHello)),
            )
            return HostHandshake(invite, identitySigner, ephemeral, sasDigits, hostHello)
        }
    }
}

class HostVerificationChallenge internal constructor(
    private val roomId: String,
    private val signer: IdentitySigner,
    val peerIdentity: IdentityPublicKey,
    private val sharedSecret: ByteArray,
    private val transcriptHash: ByteArray,
    private val expectedSas: String,
    val verificationRequired: VerificationRequired,
) {
    val peerIdentityFingerprint: String
        get() = peerIdentity.fingerprint

    val isAuthorized: Boolean
        get() = authorization != null

    @Volatile
    private var authorization: HostAuthorization? = null

    @Synchronized
    fun verifySas(code: String): HostAuthorization {
        authorization?.let { return it }
        if (!constantTimeSasEquals(expectedSas, code)) {
            throw AssistProtocolException("SAS verification failed")
        }

        val unsignedResult = VerificationResult(
            roomId = roomId,
            transcriptHash = encodeBase64Url(transcriptHash),
            accepted = true,
            peerIdentityFingerprint = peerIdentity.fingerprint,
            signature = SIGNATURE_PLACEHOLDER,
        )
        val result = unsignedResult.copy(
            signature = sign(signer, verificationResultSigningPayload(unsignedResult)),
        )
        val session = AuthorizedAssistCryptoSession(
            roomId = roomId,
            localRole = Role.HOST,
            peerIdentityFingerprint = peerIdentity.fingerprint,
            sharedSecret = sharedSecret,
            transcriptHash = transcriptHash,
        )
        sharedSecret.fill(0)
        return HostAuthorization(result, session).also { authorization = it }
    }

    @Synchronized
    fun requireAuthorizedSession(): AuthorizedAssistCryptoSession =
        authorization?.session
            ?: throw AssistProtocolException("Handshake has not been authorized by SAS verification")
}

data class HostAuthorization(
    val verificationResult: VerificationResult,
    val session: AuthorizedAssistCryptoSession,
)

class AssistHandshake private constructor(
    private val invite: Invite,
    private val identitySigner: IdentitySigner,
    private val ephemeral: EphemeralKeyAgreement,
    private val sasDigits: Int,
    private val secureRandom: SecureRandom,
) {
    private var pending: PendingAssistHandshake? = null
    private var verificationSeen = false
    @Volatile
    private var authorizedSession: AuthorizedAssistCryptoSession? = null

    @Synchronized
    fun receiveHostHello(message: HostHello): AssistHello {
        if (pending != null) {
            throw AssistProtocolException("HostHello has already been processed")
        }
        requireHelloMatchesInvite(message.roomId, message.inviteNonce, invite)
        if (message.identity != invite.hostIdentity) {
            throw AssistProtocolException("HostHello identity does not match invite")
        }
        requireValidSignature(
            message.identity,
            hostHelloSigningPayload(message),
            message.signature,
            "HostHello signature is invalid",
        )

        val hostHash = hostHelloHash(message)
        val unsignedAssistHello = AssistHello(
            roomId = invite.roomId,
            inviteNonce = invite.inviteNonce,
            identity = identitySigner.identity,
            ephemeralPublicKey = ephemeral.publicKey,
            nonce = randomNonce(secureRandom),
            hostHelloHash = encodeBase64Url(hostHash),
            signature = SIGNATURE_PLACEHOLDER,
        )
        val assistHello = unsignedAssistHello.copy(
            signature = sign(identitySigner, assistHelloSigningPayload(unsignedAssistHello)),
        )
        val transcriptHash = handshakeTranscriptHash(message, assistHello)
        val sharedSecret = ephemeral.sharedSecret(message.ephemeralPublicKey)
        pending = PendingAssistHandshake(
            hostIdentity = message.identity,
            transcriptHash = transcriptHash,
            sharedSecret = sharedSecret,
            sasCode = deriveSasCode(sharedSecret, transcriptHash, sasDigits),
        )
        return assistHello
    }

    @Synchronized
    fun receiveVerificationRequired(message: VerificationRequired): AssistVerificationChallenge {
        val state = pending ?: throw AssistProtocolException("HostHello must be processed first")
        if (verificationSeen) {
            throw AssistProtocolException("VerificationRequired has already been processed")
        }
        requireVerificationMatchesState(
            roomId = message.roomId,
            transcriptHash = message.transcriptHash,
            peerFingerprint = message.peerIdentityFingerprint,
            expectedPeerFingerprint = identitySigner.identity.fingerprint,
            invite = invite,
            state = state,
        )
        if (message.sasDigits != sasDigits) {
            throw AssistProtocolException("VerificationRequired uses unexpected SAS length")
        }
        requireValidSignature(
            state.hostIdentity,
            verificationRequiredSigningPayload(message),
            message.signature,
            "VerificationRequired signature is invalid",
        )
        verificationSeen = true
        return AssistVerificationChallenge(
            sasCode = state.sasCode,
            digits = sasDigits,
            hostIdentity = state.hostIdentity,
            localIdentity = identitySigner.identity,
        )
    }

    @Synchronized
    fun receiveVerificationResult(message: VerificationResult): AuthorizedAssistCryptoSession {
        authorizedSession?.let { return it }
        val state = pending ?: throw AssistProtocolException("HostHello must be processed first")
        if (!verificationSeen) {
            throw AssistProtocolException("VerificationRequired must be processed first")
        }
        requireVerificationMatchesState(
            roomId = message.roomId,
            transcriptHash = message.transcriptHash,
            peerFingerprint = message.peerIdentityFingerprint,
            expectedPeerFingerprint = identitySigner.identity.fingerprint,
            invite = invite,
            state = state,
        )
        requireValidSignature(
            state.hostIdentity,
            verificationResultSigningPayload(message),
            message.signature,
            "VerificationResult signature is invalid",
        )
        if (!message.accepted) {
            state.sharedSecret.fill(0)
            throw AssistProtocolException(message.reason ?: "Host rejected SAS verification")
        }
        val session = AuthorizedAssistCryptoSession(
            roomId = invite.roomId,
            localRole = Role.ASSIST,
            peerIdentityFingerprint = state.hostIdentity.fingerprint,
            sharedSecret = state.sharedSecret,
            transcriptHash = state.transcriptHash,
        )
        state.sharedSecret.fill(0)
        authorizedSession = session
        return session
    }

    @Synchronized
    fun requireAuthorizedSession(): AuthorizedAssistCryptoSession =
        authorizedSession
            ?: throw AssistProtocolException("Handshake has not been authorized by SAS verification")

    companion object {
        fun create(
            invite: Invite,
            identitySigner: IdentitySigner,
            sasDigits: Int = 6,
            secureRandom: SecureRandom = SecureRandom(),
        ): AssistHandshake {
            requireSasDigits(sasDigits)
            return AssistHandshake(
                invite,
                identitySigner,
                EphemeralKeyAgreement.generate(secureRandom),
                sasDigits,
                secureRandom,
            )
        }
    }
}

data class AssistVerificationChallenge(
    val sasCode: String,
    val digits: Int,
    val hostIdentity: IdentityPublicKey,
    val localIdentity: IdentityPublicKey,
)

private data class PendingAssistHandshake(
    val hostIdentity: IdentityPublicKey,
    val transcriptHash: ByteArray,
    val sharedSecret: ByteArray,
    val sasCode: String,
)

private fun requireHelloMatchesInvite(roomId: String, inviteNonce: String, invite: Invite) {
    if (roomId != invite.roomId || inviteNonce != invite.inviteNonce) {
        throw AssistProtocolException("Handshake hello does not match invite")
    }
}

private fun requireVerificationMatchesState(
    roomId: String,
    transcriptHash: String,
    peerFingerprint: String,
    expectedPeerFingerprint: String,
    invite: Invite,
    state: PendingAssistHandshake,
) {
    if (roomId != invite.roomId || peerFingerprint != expectedPeerFingerprint) {
        throw AssistProtocolException("Verification message does not match this handshake")
    }
    requireEqualBytes(
        state.transcriptHash,
        decodeCanonicalBase64Url(transcriptHash, TRANSCRIPT_HASH_BYTES, "transcript hash"),
        "Verification message has the wrong transcript hash",
    )
}

private fun requireValidSignature(
    identity: IdentityPublicKey,
    payload: ByteArray,
    signature: String,
    errorMessage: String,
) {
    val signatureBytes = decodeCanonicalBase64Url(signature, "signature")
    if (!IdentitySignatures.verify(identity, payload, signatureBytes)) {
        throw AssistProtocolException(errorMessage)
    }
}

private fun sign(signer: IdentitySigner, payload: ByteArray): String =
    encodeBase64Url(signer.sign(payload))

private fun randomNonce(secureRandom: SecureRandom): String =
    encodeBase64Url(ByteArray(HANDSHAKE_NONCE_BYTES).also(secureRandom::nextBytes))

private fun requireEqualBytes(expected: ByteArray, actual: ByteArray, message: String) {
    if (!MessageDigest.isEqual(expected, actual)) {
        throw AssistProtocolException(message)
    }
}

private fun constantTimeSasEquals(expected: String, supplied: String): Boolean {
    if (supplied.length != expected.length || supplied.any { !it.isDigit() }) return false
    return MessageDigest.isEqual(
        expected.toByteArray(StandardCharsets.US_ASCII),
        supplied.toByteArray(StandardCharsets.US_ASCII),
    )
}

private fun requireSasDigits(digits: Int) {
    if (digits !in setOf(6, 8)) {
        throw AssistProtocolException("SAS must contain 6 or 8 digits")
    }
}

private val SIGNATURE_PLACEHOLDER = encodeBase64Url(ByteArray(64))
