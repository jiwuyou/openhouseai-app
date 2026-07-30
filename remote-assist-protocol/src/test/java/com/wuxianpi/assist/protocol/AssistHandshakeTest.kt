package com.wuxianpi.assist.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistHandshakeTest {
    @Test
    fun normalHandshakeUsesSameSasAndExposesPeerFingerprintsAfterAuthorization() {
        val fixture = prepareHandshake(200)
        val assistChallenge = fixture.assist.receiveVerificationRequired(
            fixture.hostChallenge.verificationRequired,
        )

        assertEquals(6, assistChallenge.sasCode.length)
        assertTrue(assistChallenge.sasCode.all(Char::isDigit))
        assertThrows(AssistProtocolException::class.java) {
            fixture.hostChallenge.requireAuthorizedSession()
        }
        assertThrows(AssistProtocolException::class.java) {
            fixture.assist.requireAuthorizedSession()
        }

        val authorization = fixture.hostChallenge.verifySas(assistChallenge.sasCode)
        val assistSession = fixture.assist.receiveVerificationResult(authorization.verificationResult)

        assertTrue(fixture.hostChallenge.isAuthorized)
        assertEquals(fixture.assistSigner.identity.fingerprint, authorization.session.peerIdentityFingerprint)
        assertEquals(fixture.hostSigner.identity.fingerprint, assistSession.peerIdentityFingerprint)
        assertEquals(authorization.session, fixture.hostChallenge.requireAuthorizedSession())
        assertEquals(assistSession, fixture.assist.requireAuthorizedSession())
    }

    @Test
    fun differentEphemeralHandshakesProduceDifferentSasCodes() {
        val hostSigner = IdentityKeys.generate(seededRandom(210))
        val assistSigner = IdentityKeys.generate(seededRandom(211))
        val invite = Invite.create(
            "wss://assist.example.test/relay",
            hostSigner.identity,
            seededRandom(212),
        )

        val first = prepareHandshake(invite, hostSigner, assistSigner, 213)
        val second = prepareHandshake(invite, hostSigner, assistSigner, 223)
        val firstSas = first.assist.receiveVerificationRequired(
            first.hostChallenge.verificationRequired,
        ).sasCode
        val secondSas = second.assist.receiveVerificationRequired(
            second.hostChallenge.verificationRequired,
        ).sasCode

        assertNotEquals(
            first.host.hostHello.ephemeralPublicKey,
            second.host.hostHello.ephemeralPublicKey,
        )
        assertNotEquals(firstSas, secondSas)
    }

    @Test
    fun eightDigitSasCompletesAuthorization() {
        val hostSigner = IdentityKeys.generate(seededRandom(220))
        val assistSigner = IdentityKeys.generate(seededRandom(221))
        val invite = Invite.create(
            "wss://assist.example.test/relay",
            hostSigner.identity,
            seededRandom(222),
        )
        val host = HostHandshake.create(
            invite,
            hostSigner,
            sasDigits = 8,
            secureRandom = seededRandom(223),
        )
        val assist = AssistHandshake.create(
            invite,
            assistSigner,
            sasDigits = 8,
            secureRandom = seededRandom(224),
        )
        val hostChallenge = host.receiveAssistHello(assist.receiveHostHello(host.hostHello))
        val assistChallenge = assist.receiveVerificationRequired(hostChallenge.verificationRequired)

        assertEquals(8, assistChallenge.sasCode.length)
        val authorization = hostChallenge.verifySas(assistChallenge.sasCode)
        assertTrue(assist.receiveVerificationResult(authorization.verificationResult).isAuthorized)
    }

    @Test
    fun mitmEphemeralReplacementAndSignatureTamperingAreRejected() {
        val hostSigner = IdentityKeys.generate(seededRandom(230))
        val assistSigner = IdentityKeys.generate(seededRandom(231))
        val invite = Invite.create(
            "wss://assist.example.test/relay",
            hostSigner.identity,
            seededRandom(232),
        )
        val originalHost = HostHandshake.create(
            invite,
            hostSigner,
            secureRandom = seededRandom(233),
        )
        val replacementHost = HostHandshake.create(
            invite,
            hostSigner,
            secureRandom = seededRandom(234),
        )
        val assist = AssistHandshake.create(
            invite,
            assistSigner,
            secureRandom = seededRandom(235),
        )
        val replacedEphemeral = originalHost.hostHello.copy(
            ephemeralPublicKey = replacementHost.hostHello.ephemeralPublicKey,
        )

        assertThrows(AssistProtocolException::class.java) {
            assist.receiveHostHello(replacedEphemeral)
        }

        val freshAssist = AssistHandshake.create(
            invite,
            assistSigner,
            secureRandom = seededRandom(236),
        )
        val signatureBytes = decodeBase64Url(originalHost.hostHello.signature, "signature")
        signatureBytes[signatureBytes.lastIndex] = (signatureBytes.last().toInt() xor 1).toByte()
        val tamperedSignature = originalHost.hostHello.copy(
            signature = encodeBase64Url(signatureBytes),
        )
        assertThrows(AssistProtocolException::class.java) {
            freshAssist.receiveHostHello(tamperedSignature)
        }

        val assistForSignatureTest = AssistHandshake.create(
            invite,
            assistSigner,
            secureRandom = seededRandom(237),
        )
        val assistHello = assistForSignatureTest.receiveHostHello(originalHost.hostHello)
        val assistSignature = decodeBase64Url(assistHello.signature, "signature")
        assistSignature[assistSignature.lastIndex] =
            (assistSignature.last().toInt() xor 1).toByte()
        assertThrows(AssistProtocolException::class.java) {
            originalHost.receiveAssistHello(
                assistHello.copy(signature = encodeBase64Url(assistSignature)),
            )
        }
    }

    @Test
    fun wrongSasDoesNotAuthorizeOrExposeApplicationEncryption() {
        val fixture = prepareHandshake(240)
        val challenge = fixture.assist.receiveVerificationRequired(
            fixture.hostChallenge.verificationRequired,
        )
        val replacement = if (challenge.sasCode[0] == '9') '0' else challenge.sasCode[0] + 1
        val wrongCode = replacement + challenge.sasCode.substring(1)

        assertThrows(AssistProtocolException::class.java) {
            fixture.hostChallenge.verifySas(wrongCode)
        }
        assertFalse(fixture.hostChallenge.isAuthorized)
        assertThrows(AssistProtocolException::class.java) {
            fixture.hostChallenge.requireAuthorizedSession().encryptMessage(Ping("blocked", 1))
        }
        assertThrows(AssistProtocolException::class.java) {
            fixture.assist.requireAuthorizedSession().encryptMessage(Ping("blocked", 1))
        }

        val authorization = fixture.hostChallenge.verifySas(challenge.sasCode)
        fixture.assist.receiveVerificationResult(authorization.verificationResult)
        assertTrue(fixture.hostChallenge.isAuthorized)
    }

    @Test
    fun handshakeMessagesRoundTripWithoutSendingSasCode() {
        val fixture = prepareHandshake(250)
        val challenge = fixture.assist.receiveVerificationRequired(
            fixture.hostChallenge.verificationRequired,
        )
        val authorization = fixture.hostChallenge.verifySas(challenge.sasCode)
        val messages: List<HandshakeMessage> = listOf(
            fixture.host.hostHello,
            fixture.assistHello,
            fixture.hostChallenge.verificationRequired,
            authorization.verificationResult,
        )

        messages.forEach { message ->
            val encoded = AssistProtocolJson.encodeHandshakeMessage(message)
            assertEquals(message, AssistProtocolJson.decodeHandshakeMessage(encoded))
            assertFalse(encoded.contains("\"sasCode\""))
            assertFalse(encoded.contains("\"verificationCode\""))
        }

        val hostHelloJson = AssistProtocolJson.encodeHandshakeMessage(fixture.host.hostHello)
        assertThrows(AssistProtocolException::class.java) {
            AssistProtocolJson.decodeHandshakeMessage(
                hostHelloJson.dropLast(1) + ",\"unknown\":true}",
            )
        }
    }

    private fun prepareHandshake(seed: Long): PreparedHandshake {
        val hostSigner = IdentityKeys.generate(seededRandom(seed))
        val assistSigner = IdentityKeys.generate(seededRandom(seed + 1))
        val invite = Invite.create(
            "wss://assist.example.test/relay",
            hostSigner.identity,
            seededRandom(seed + 2),
        )
        return prepareHandshake(invite, hostSigner, assistSigner, seed + 3)
    }

    private fun prepareHandshake(
        invite: Invite,
        hostSigner: IdentitySigner,
        assistSigner: IdentitySigner,
        seed: Long,
    ): PreparedHandshake {
        val host = HostHandshake.create(
            invite,
            hostSigner,
            secureRandom = seededRandom(seed),
        )
        val assist = AssistHandshake.create(
            invite,
            assistSigner,
            secureRandom = seededRandom(seed + 1),
        )
        val assistHello = assist.receiveHostHello(host.hostHello)
        val hostChallenge = host.receiveAssistHello(assistHello)
        return PreparedHandshake(
            hostSigner,
            assistSigner,
            host,
            assist,
            assistHello,
            hostChallenge,
        )
    }

    private data class PreparedHandshake(
        val hostSigner: IdentitySigner,
        val assistSigner: IdentitySigner,
        val host: HostHandshake,
        val assist: AssistHandshake,
        val assistHello: AssistHello,
        val hostChallenge: HostVerificationChallenge,
    )
}
