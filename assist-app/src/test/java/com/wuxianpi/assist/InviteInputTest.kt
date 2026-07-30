package com.wuxianpi.assist

import com.wuxianpi.assist.protocol.AssistProtocolException
import com.wuxianpi.assist.protocol.IdentityKeys
import com.wuxianpi.assist.protocol.Invite
import org.junit.Assert.assertEquals
import org.junit.Test

class InviteInputTest {
    @Test
    fun parsesDirectInvite() {
        val invite = invite()

        assertEquals(invite, InviteInput.parse(invite.toUriString()))
    }

    @Test
    fun extractsInviteFromSharedMessage() {
        val invite = invite()
        val shared = "Please join my Rescue AI session: ${invite.toUriString()}\nThank you"

        assertEquals(invite, InviteInput.parse(shared))
    }

    @Test(expected = AssistProtocolException::class)
    fun rejectsTextWithoutInvite() {
        InviteInput.parse("https://example.com/not-an-invite")
    }

    private fun invite(): Invite = Invite.create(
        relayUrl = "wss://relay.example.com/relay",
        hostIdentity = IdentityKeys.generate().identity,
    )
}
