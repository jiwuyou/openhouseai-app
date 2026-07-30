package com.wuxianpi.assist.protocol

import java.security.SecureRandom

internal data class CompletedHandshake(
    val invite: Invite,
    val hostSigner: IdentitySigner,
    val assistSigner: IdentitySigner,
    val hostSession: AuthorizedAssistCryptoSession,
    val assistSession: AuthorizedAssistCryptoSession,
    val sasCode: String,
)

internal fun completeHandshake(seed: Long = 100): CompletedHandshake {
    val hostSigner = IdentityKeys.generate(seededRandom(seed))
    val assistSigner = IdentityKeys.generate(seededRandom(seed + 1))
    val invite = Invite.create(
        "wss://assist.example.test/relay",
        hostSigner.identity,
        seededRandom(seed + 2),
    )
    val host = HostHandshake.create(invite, hostSigner, secureRandom = seededRandom(seed + 3))
    val assist = AssistHandshake.create(invite, assistSigner, secureRandom = seededRandom(seed + 4))
    val assistHello = assist.receiveHostHello(host.hostHello)
    val hostChallenge = host.receiveAssistHello(assistHello)
    val assistChallenge = assist.receiveVerificationRequired(hostChallenge.verificationRequired)
    val authorization = hostChallenge.verifySas(assistChallenge.sasCode)
    val assistSession = assist.receiveVerificationResult(authorization.verificationResult)
    return CompletedHandshake(
        invite = invite,
        hostSigner = hostSigner,
        assistSigner = assistSigner,
        hostSession = authorization.session,
        assistSession = assistSession,
        sasCode = assistChallenge.sasCode,
    )
}

internal fun seededRandom(seed: Long): SecureRandom =
    SecureRandom.getInstance("SHA1PRNG").apply { setSeed(seed) }
