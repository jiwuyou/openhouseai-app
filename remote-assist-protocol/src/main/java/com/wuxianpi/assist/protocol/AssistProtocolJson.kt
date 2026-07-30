package com.wuxianpi.assist.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

@OptIn(ExperimentalSerializationApi::class)
object AssistProtocolJson {
    private val json = Json {
        classDiscriminator = "type"
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        allowStructuredMapKeys = false
        allowSpecialFloatingPointValues = false
        useAlternativeNames = false
    }

    fun encodeMessage(message: AssistMessage): String = encodeStrict {
        json.encodeToString(AssistMessage.serializer(), message)
    }

    fun decodeMessage(value: String): AssistMessage = decodeStrict("application message") {
        requireWireHeader(value, requireType = true)
        json.decodeFromString<AssistMessage>(value)
    }

    fun encodeFrame(frame: EncryptedFrame): String = encodeStrict {
        json.encodeToString(frame)
    }

    fun decodeFrame(value: String): EncryptedFrame = decodeStrict("encrypted frame") {
        requireWireHeader(value, requireType = false)
        json.decodeFromString<EncryptedFrame>(value)
    }

    fun encodeRelayEnvelope(envelope: RelayEnvelope): String = encodeStrict {
        json.encodeToString(envelope)
    }

    fun decodeRelayEnvelope(value: String): RelayEnvelope = decodeStrict("relay envelope") {
        requireWireHeader(value, requireType = false)
        json.decodeFromString<RelayEnvelope>(value)
    }

    fun encodeRelayControlEvent(event: RelayControlEvent): String = encodeStrict {
        json.encodeToString(RelayControlEvent.serializer(), event)
    }

    fun decodeRelayControlEvent(value: String): RelayControlEvent =
        decodeStrict("relay control event") {
            requireRelayControlHeader(value)
            json.decodeFromString<RelayControlEvent>(value)
        }

    fun encodeHandshakeMessage(message: HandshakeMessage): String = encodeStrict {
        json.encodeToString(HandshakeMessage.serializer(), message)
    }

    fun decodeHandshakeMessage(value: String): HandshakeMessage =
        decodeStrict("handshake message") {
            requireHandshakeHeader(value)
            json.decodeFromString<HandshakeMessage>(value)
        }

    fun decodeRelayTextFrame(value: String): RelayTextFrame = decodeStrict("relay text frame") {
        val objectValue = requireJsonObject(value)
        if ("relay" in objectValue) {
            RelayTextFrame.Control(decodeRelayControlEvent(value))
        } else if ("handshake" in objectValue) {
            RelayTextFrame.Handshake(decodeHandshakeMessage(value))
        } else {
            RelayTextFrame.Application(decodeRelayEnvelope(value))
        }
    }

    private fun requireWireHeader(value: String, requireType: Boolean) {
        val objectValue = requireJsonObject(value)
        val version = objectValue["version"]?.jsonPrimitive?.intOrNull
            ?: throw AssistProtocolException("Protocol payload requires an integer version")
        requireVersion(version)
        if (requireType) {
            val type = objectValue["type"]?.jsonPrimitive?.content
            if (type.isNullOrBlank()) {
                throw AssistProtocolException("Application message requires a type")
            }
        }
    }

    private fun requireRelayControlHeader(value: String) {
        val objectValue = requireJsonObject(value)
        val relay = objectValue["relay"]?.jsonPrimitive?.intOrNull
            ?: throw AssistProtocolException("Relay control event requires an integer relay field")
        requireRelayControlVersion(relay)
        val type = objectValue["type"]?.jsonPrimitive?.content
        if (type.isNullOrBlank()) {
            throw AssistProtocolException("Relay control event requires a type")
        }
    }

    private fun requireHandshakeHeader(value: String) {
        val objectValue = requireJsonObject(value)
        val version = objectValue["version"]?.jsonPrimitive?.intOrNull
            ?: throw AssistProtocolException("Handshake message requires an integer version")
        requireVersion(version)
        val handshake = objectValue["handshake"]?.jsonPrimitive?.intOrNull
            ?: throw AssistProtocolException("Handshake message requires an integer handshake marker")
        if (handshake != HANDSHAKE_MARKER_VERSION) {
            throw AssistProtocolException("Unsupported handshake marker version: $handshake")
        }
        val type = objectValue["type"]?.jsonPrimitive?.content
        if (type.isNullOrBlank()) {
            throw AssistProtocolException("Handshake message requires a type")
        }
    }

    private fun requireJsonObject(value: String): JsonObject {
        val element = json.parseToJsonElement(value)
        return element as? JsonObject
            ?: throw AssistProtocolException("Protocol payload must be a JSON object")
    }

    private inline fun <T> decodeStrict(label: String, block: () -> T): T = try {
        block()
    } catch (error: AssistProtocolException) {
        throw error
    } catch (error: SerializationException) {
        throw AssistProtocolException("Invalid $label", error)
    } catch (error: IllegalArgumentException) {
        throw AssistProtocolException("Invalid $label", error)
    }

    private inline fun encodeStrict(block: () -> String): String = try {
        block()
    } catch (error: SerializationException) {
        throw AssistProtocolException("Cannot encode protocol payload", error)
    }
}
