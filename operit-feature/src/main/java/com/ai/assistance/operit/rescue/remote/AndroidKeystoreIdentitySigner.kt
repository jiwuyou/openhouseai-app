package com.ai.assistance.operit.rescue.remote

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.wuxianpi.assist.protocol.IdentityPublicKey
import com.wuxianpi.assist.protocol.IdentitySigner
import com.wuxianpi.assist.protocol.JcaIdentitySigner
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec

internal class AndroidKeystoreIdentitySigner private constructor(
    private val delegate: JcaIdentitySigner,
) : IdentitySigner {
    override val identity: IdentityPublicKey
        get() = delegate.identity

    override fun sign(payload: ByteArray): ByteArray = delegate.sign(payload)

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val DEFAULT_ALIAS = "wuxianpi_rescue_remote_assist_identity_v1"

        fun getOrCreate(alias: String = DEFAULT_ALIAS): AndroidKeystoreIdentitySigner {
            val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            val existingPrivateKey = keyStore.getKey(alias, null) as? java.security.PrivateKey
            val existingPublicKey = keyStore.getCertificate(alias)?.publicKey
            if (existingPrivateKey != null && existingPublicKey != null) {
                return AndroidKeystoreIdentitySigner(
                    JcaIdentitySigner.fromKeys(existingPublicKey, existingPrivateKey),
                )
            }

            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)
            generator.initialize(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                )
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
            return AndroidKeystoreIdentitySigner(
                JcaIdentitySigner.fromKeyPair(generator.generateKeyPair()),
            )
        }
    }
}
