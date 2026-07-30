package com.wuxianpi.assist.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.SecureRandom

class InviteTest {
    @Test
    fun inviteRoundTripPreservesAllFields() {
        val random = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(42L) }
        val hostIdentity = IdentityKeys.generate(seededRandom(41)).identity
        val invite = Invite.create("wss://assist.example.test/relay?region=cn", hostIdentity, random)

        assertEquals(invite, Invite.parse(invite.toUriString()))
        assertEquals(16, decodeBase64Url(invite.roomId, "room").size)
        assertEquals(32, invite.inviteNonceBytes().size)
        assertEquals(hostIdentity.fingerprint, invite.hostIdentity.fingerprint)
        assertEquals(false, invite.toUriString().contains("secret", ignoreCase = true))
    }

    @Test
    fun inviteRejectsUnknownDuplicateOrMalformedFields() {
        val invite = Invite.create(
            "wss://assist.example.test/relay",
            IdentityKeys.generate(seededRandom(50)).identity,
        )
        val encoded = invite.toUriString()

        assertThrows(AssistProtocolException::class.java) {
            Invite.parse(encoded + "&extra=value")
        }
        assertThrows(AssistProtocolException::class.java) {
            Invite.parse(encoded + "&room=${invite.roomId}")
        }
        assertThrows(AssistProtocolException::class.java) {
            Invite.parse(encoded.replace("v=1", "v=2"))
        }
        assertThrows(AssistProtocolException::class.java) {
            Invite.parse(encoded.replace("wuxianpi-assist://join", "https://join"))
        }
        val unrelatedFingerprint = encodeBase64Url(ByteArray(32) { (it + 5).toByte() })
        assertThrows(AssistProtocolException::class.java) {
            Invite.parse(encoded.replace(invite.hostIdentity.fingerprint, unrelatedFingerprint))
        }
    }
}
