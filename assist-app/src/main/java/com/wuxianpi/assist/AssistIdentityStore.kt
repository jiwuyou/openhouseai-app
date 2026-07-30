package com.wuxianpi.assist

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.wuxianpi.assist.protocol.IdentitySigner
import com.wuxianpi.assist.protocol.JcaIdentitySigner
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import java.util.Calendar
import java.util.Date
import javax.security.auth.x500.X500Principal

class AssistIdentityStore(
    private val alias: String = IDENTITY_ALIAS,
) {
    @Synchronized
    fun getOrCreateSigner(): IdentitySigner {
        val keyStore = loadKeyStore()
        readSigner(keyStore)?.let { return it }

        generateIdentity()
        return readSigner(loadKeyStore())
            ?: error("Android Keystore did not retain the generated assist identity")
    }

    private fun readSigner(keyStore: KeyStore): IdentitySigner? {
        val privateKey = keyStore.getKey(alias, null) as? PrivateKey ?: return null
        val publicKey = keyStore.getCertificate(alias)?.publicKey ?: return null
        return JcaIdentitySigner.fromKeys(publicKey, privateKey)
    }

    private fun generateIdentity() {
        val now = Date()
        val expires = Calendar.getInstance().apply {
            time = now
            add(Calendar.YEAR, 30)
        }.time
        val parameters = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setCertificateSubject(X500Principal("CN=WuxianPi Assist Identity"))
            .setCertificateSerialNumber(BigInteger.ONE)
            .setCertificateNotBefore(now)
            .setCertificateNotAfter(expires)
            .setUserAuthenticationRequired(false)
            .build()

        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEY_STORE).run {
            initialize(parameters)
            generateKeyPair()
        }
    }

    private fun loadKeyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply {
        load(null)
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val IDENTITY_ALIAS = "wuxianpi_assist_identity_v1"
    }
}
