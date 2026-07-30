package com.wuxianpi.assist

import com.wuxianpi.assist.protocol.AssistProtocolException
import com.wuxianpi.assist.protocol.Invite

object InviteInput {
    private const val PREFIX = "wuxianpi-assist://join"

    fun parse(raw: String): Invite {
        val trimmed = raw.trim()
        val start = trimmed.indexOf(PREFIX)
        if (start < 0) {
            throw AssistProtocolException("No WuxianPi Assist invitation was found")
        }
        val candidate = trimmed.substring(start)
            .takeWhile { !it.isWhitespace() }
            .trimEnd('.', ',', ';', ')', ']', '}')
        return Invite.parse(candidate)
    }
}
