package com.wuxianpi.assist.protocol

import kotlinx.serialization.Serializable
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64

const val ASSIST_PROTOCOL_VERSION: Int = 1

internal const val ROOM_ID_BYTES = 16
internal const val HANDSHAKE_NONCE_BYTES = 32

class AssistProtocolException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

@Serializable
enum class Role {
    HOST,
    ASSIST,
}

@Serializable
enum class Permission {
    VIEW,
    COLLABORATE,
}

@Serializable
enum class Direction {
    HOST_TO_ASSIST,
    ASSIST_TO_HOST,
}

val Role.outboundDirection: Direction
    get() = when (this) {
        Role.HOST -> Direction.HOST_TO_ASSIST
        Role.ASSIST -> Direction.ASSIST_TO_HOST
    }

val Role.inboundDirection: Direction
    get() = when (this) {
        Role.HOST -> Direction.ASSIST_TO_HOST
        Role.ASSIST -> Direction.HOST_TO_ASSIST
    }

data class Invite(
    val relayUrl: String,
    val roomId: String,
    val hostIdentity: IdentityPublicKey,
    val inviteNonce: String,
) {
    init {
        validateRelayUrl(relayUrl)
        decodeCanonicalBase64Url(roomId, ROOM_ID_BYTES, "room")
        decodeCanonicalBase64Url(inviteNonce, HANDSHAKE_NONCE_BYTES, "invite nonce")
    }

    fun toUriString(): String = buildString {
        append("wuxianpi-assist://join")
        append("?v=")
        append(ASSIST_PROTOCOL_VERSION)
        append("&relay=")
        append(urlEncode(relayUrl))
        append("&room=")
        append(urlEncode(roomId))
        append("&host_key=")
        append(urlEncode(hostIdentity.encodedKey))
        append("&host_fp=")
        append(urlEncode(hostIdentity.fingerprint))
        append("&nonce=")
        append(urlEncode(inviteNonce))
    }

    fun inviteNonceBytes(): ByteArray =
        decodeCanonicalBase64Url(inviteNonce, HANDSHAKE_NONCE_BYTES, "invite nonce")

    companion object {
        fun create(
            relayUrl: String,
            hostIdentity: IdentityPublicKey,
            secureRandom: SecureRandom = SecureRandom(),
        ): Invite {
            val room = ByteArray(ROOM_ID_BYTES).also(secureRandom::nextBytes)
            val nonce = ByteArray(HANDSHAKE_NONCE_BYTES).also(secureRandom::nextBytes)
            return Invite(
                relayUrl = relayUrl,
                roomId = encodeBase64Url(room),
                hostIdentity = hostIdentity,
                inviteNonce = encodeBase64Url(nonce),
            )
        }

        fun parse(value: String): Invite {
            val uri = try {
                URI(value)
            } catch (error: Exception) {
                throw AssistProtocolException("Invalid assist invite URI", error)
            }
            if (uri.scheme != "wuxianpi-assist" || uri.rawAuthority != "join") {
                throw AssistProtocolException("Invite must use wuxianpi-assist://join")
            }
            if (!uri.rawPath.isNullOrEmpty() || uri.rawFragment != null) {
                throw AssistProtocolException("Invite path and fragment must be empty")
            }

            val query = parseStrictQuery(uri.rawQuery)
            val expectedKeys = setOf("v", "relay", "room", "host_key", "host_fp", "nonce")
            if (query.keys != expectedKeys) {
                throw AssistProtocolException(
                    "Invite must contain exactly v, relay, room, host_key, host_fp and nonce",
                )
            }
            if (query.getValue("v") != ASSIST_PROTOCOL_VERSION.toString()) {
                throw AssistProtocolException("Unsupported assist protocol version")
            }
            return Invite(
                relayUrl = query.getValue("relay"),
                roomId = query.getValue("room"),
                hostIdentity = IdentityPublicKey(
                    algorithm = IdentityAlgorithm.ECDSA_P256_SHA256,
                    encodedKey = query.getValue("host_key"),
                    fingerprint = query.getValue("host_fp"),
                ),
                inviteNonce = query.getValue("nonce"),
            )
        }
    }
}

internal fun validateRoomId(roomId: String) {
    decodeCanonicalBase64Url(roomId, ROOM_ID_BYTES, "room")
}

internal fun encodeBase64Url(value: ByteArray): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(value)

internal fun decodeBase64Url(value: String, field: String): ByteArray {
    if (value.isEmpty() || '=' in value) {
        throw AssistProtocolException("$field must be unpadded base64url")
    }
    return try {
        Base64.getUrlDecoder().decode(value)
    } catch (error: IllegalArgumentException) {
        throw AssistProtocolException("$field must be valid base64url", error)
    }
}

internal fun decodeCanonicalBase64Url(value: String, field: String): ByteArray {
    val decoded = decodeBase64Url(value, field)
    if (encodeBase64Url(decoded) != value) {
        throw AssistProtocolException("$field must use canonical unpadded base64url")
    }
    return decoded
}

internal fun decodeCanonicalBase64Url(value: String, size: Int, field: String): ByteArray {
    val decoded = decodeCanonicalBase64Url(value, field)
    if (decoded.size != size) {
        throw AssistProtocolException("$field must encode exactly $size bytes")
    }
    return decoded
}

internal fun validateRelayUrl(value: String) {
    val uri = try {
        URI(value)
    } catch (error: Exception) {
        throw AssistProtocolException("Invalid relay URL", error)
    }
    if (uri.scheme !in setOf("ws", "wss") || uri.host.isNullOrBlank()) {
        throw AssistProtocolException("Relay URL must be an absolute ws:// or wss:// URL")
    }
    if (uri.userInfo != null || uri.fragment != null) {
        throw AssistProtocolException("Relay URL cannot contain user info or a fragment")
    }
}

private fun parseStrictQuery(rawQuery: String?): Map<String, String> {
    if (rawQuery.isNullOrEmpty()) {
        throw AssistProtocolException("Invite query is missing")
    }
    val result = linkedMapOf<String, String>()
    rawQuery.split('&').forEach { entry ->
        val separator = entry.indexOf('=')
        if (separator <= 0) {
            throw AssistProtocolException("Invalid invite query entry")
        }
        val key = urlDecode(entry.substring(0, separator))
        val value = urlDecode(entry.substring(separator + 1))
        if (result.put(key, value) != null) {
            throw AssistProtocolException("Duplicate invite field: $key")
        }
    }
    return result
}

private fun urlEncode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

private fun urlDecode(value: String): String = try {
    URLDecoder.decode(value, StandardCharsets.UTF_8.name())
} catch (error: IllegalArgumentException) {
    throw AssistProtocolException("Invalid percent encoding in invite", error)
}
