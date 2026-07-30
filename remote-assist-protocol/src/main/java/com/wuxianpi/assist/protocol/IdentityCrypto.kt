package com.wuxianpi.assist.protocol

import kotlinx.serialization.Serializable
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECFieldFp
import java.security.spec.ECParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

@Serializable
enum class IdentityAlgorithm {
    ECDSA_P256_SHA256,
}

@Serializable
data class IdentityPublicKey(
    val algorithm: IdentityAlgorithm,
    val encodedKey: String,
    val fingerprint: String,
) {
    init {
        val encoded = decodeCanonicalBase64Url(encodedKey, "identity public key")
        decodeP256PublicKey(encoded, "identity public key")
        val expected = identityFingerprint(encoded)
        val supplied = decodeCanonicalBase64Url(fingerprint, IDENTITY_FINGERPRINT_BYTES, "fingerprint")
        if (!MessageDigest.isEqual(expected, supplied)) {
            throw AssistProtocolException("Identity fingerprint does not match public key")
        }
    }

    fun toJcaPublicKey(): PublicKey =
        decodeP256PublicKey(decodeCanonicalBase64Url(encodedKey, "identity public key"), "identity public key")

    companion object {
        fun fromPublicKey(publicKey: PublicKey): IdentityPublicKey {
            requireP256PublicKey(publicKey, "identity public key")
            val encoded = publicKey.encoded
                ?: throw AssistProtocolException("Identity public key must have X.509 encoding")
            return IdentityPublicKey(
                algorithm = IdentityAlgorithm.ECDSA_P256_SHA256,
                encodedKey = encodeBase64Url(encoded),
                fingerprint = encodeBase64Url(identityFingerprint(encoded)),
            )
        }
    }
}

interface IdentitySigner {
    val identity: IdentityPublicKey

    fun sign(payload: ByteArray): ByteArray
}

class JcaIdentitySigner private constructor(
    override val identity: IdentityPublicKey,
    private val privateKey: PrivateKey,
) : IdentitySigner {
    override fun sign(payload: ByteArray): ByteArray = try {
        Signature.getInstance(IDENTITY_SIGNATURE_ALGORITHM).run {
            initSign(privateKey)
            update(payload)
            sign()
        }
    } catch (error: Exception) {
        throw AssistProtocolException("Unable to sign assist handshake", error)
    }

    companion object {
        fun fromKeyPair(keyPair: KeyPair): JcaIdentitySigner =
            fromKeys(keyPair.public, keyPair.private)

        // Android Keystore private keys can be supplied here without exporting key material.
        fun fromKeys(publicKey: PublicKey, privateKey: PrivateKey): JcaIdentitySigner {
            val identity = IdentityPublicKey.fromPublicKey(publicKey)
            if (privateKey.algorithm != "EC") {
                throw AssistProtocolException("Identity private key must use EC")
            }
            return JcaIdentitySigner(identity, privateKey)
        }
    }
}

object IdentityKeys {
    fun generate(secureRandom: SecureRandom = SecureRandom()): JcaIdentitySigner {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec(P256_CURVE), secureRandom)
        return JcaIdentitySigner.fromKeyPair(generator.generateKeyPair())
    }
}

object IdentitySignatures {
    fun verify(identity: IdentityPublicKey, payload: ByteArray, signature: ByteArray): Boolean = try {
        Signature.getInstance(IDENTITY_SIGNATURE_ALGORITHM).run {
            initVerify(identity.toJcaPublicKey())
            update(payload)
            verify(signature)
        }
    } catch (error: Exception) {
        throw AssistProtocolException("Unable to verify assist handshake signature", error)
    }
}

@Serializable
enum class EphemeralKeyAlgorithm {
    ECDH_P256,
}

@Serializable
data class EphemeralPublicKey(
    val algorithm: EphemeralKeyAlgorithm,
    val encodedKey: String,
) {
    init {
        decodeP256PublicKey(
            decodeCanonicalBase64Url(encodedKey, "ephemeral public key"),
            "ephemeral public key",
        )
    }

    fun toJcaPublicKey(): PublicKey =
        decodeP256PublicKey(
            decodeCanonicalBase64Url(encodedKey, "ephemeral public key"),
            "ephemeral public key",
        )

    companion object {
        fun fromPublicKey(publicKey: PublicKey): EphemeralPublicKey {
            requireP256PublicKey(publicKey, "ephemeral public key")
            return EphemeralPublicKey(
                algorithm = EphemeralKeyAlgorithm.ECDH_P256,
                encodedKey = encodeBase64Url(
                    publicKey.encoded
                        ?: throw AssistProtocolException("Ephemeral public key must have X.509 encoding"),
                ),
            )
        }
    }
}

internal class EphemeralKeyAgreement private constructor(
    private val keyPair: KeyPair,
    val publicKey: EphemeralPublicKey,
) {
    fun sharedSecret(peerPublicKey: EphemeralPublicKey): ByteArray = try {
        KeyAgreement.getInstance("ECDH").run {
            init(keyPair.private)
            doPhase(peerPublicKey.toJcaPublicKey(), true)
            generateSecret()
        }
    } catch (error: Exception) {
        throw AssistProtocolException("Unable to complete ephemeral ECDH", error)
    }

    companion object {
        fun generate(secureRandom: SecureRandom): EphemeralKeyAgreement {
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec(P256_CURVE), secureRandom)
            val keyPair = generator.generateKeyPair()
            return EphemeralKeyAgreement(keyPair, EphemeralPublicKey.fromPublicKey(keyPair.public))
        }
    }
}

private fun identityFingerprint(encodedKey: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(encodedKey)

private fun decodeP256PublicKey(encoded: ByteArray, field: String): PublicKey = try {
    KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded)).also {
        requireP256PublicKey(it, field)
    }
} catch (error: AssistProtocolException) {
    throw error
} catch (error: Exception) {
    throw AssistProtocolException("$field is not a valid EC public key", error)
}

private fun requireP256PublicKey(publicKey: PublicKey, field: String) {
    val ecKey = publicKey as? ECPublicKey
        ?: throw AssistProtocolException("$field must use EC")
    if (!sameCurve(ecKey.params, p256Parameters)) {
        throw AssistProtocolException("$field must use the P-256 curve")
    }
}

private fun sameCurve(actual: ECParameterSpec, expected: ECParameterSpec): Boolean {
    val actualField = actual.curve.field as? ECFieldFp ?: return false
    val expectedField = expected.curve.field as? ECFieldFp ?: return false
    return actualField.p == expectedField.p &&
        actual.curve.a == expected.curve.a &&
        actual.curve.b == expected.curve.b &&
        actual.generator == expected.generator &&
        actual.order == expected.order &&
        actual.cofactor == expected.cofactor
}

private val p256Parameters: ECParameterSpec by lazy {
    AlgorithmParameters.getInstance("EC").run {
        init(ECGenParameterSpec(P256_CURVE))
        getParameterSpec(ECParameterSpec::class.java)
    }
}

private const val IDENTITY_SIGNATURE_ALGORITHM = "SHA256withECDSA"
private const val P256_CURVE = "secp256r1"
private const val IDENTITY_FINGERPRINT_BYTES = 32
