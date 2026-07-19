package com.wuxianpi.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class GatewayCredentials(
    val adminUrl: String,
    val token: String,
    val clientId: String,
)

/** Stores the gateway token encrypted by a non-exportable Android Keystore key. */
class GatewayTokenStore(context: Context) {
    private val preferences = context.getSharedPreferences("wuxianpi_gateway", Context.MODE_PRIVATE)

    fun load(): GatewayCredentials? {
        val adminUrl = preferences.getString(KEY_URL, null) ?: return null
        val encrypted = preferences.getString(KEY_TOKEN, null) ?: return null
        val clientId = preferences.getString(KEY_CLIENT, null) ?: return null
        return runCatching { GatewayCredentials(adminUrl, decrypt(encrypted), clientId) }.getOrNull()
    }

    fun save(adminUrl: String, token: String, clientId: String = currentOrNewClientId()) {
        require(adminUrl.startsWith("http://127.0.0.1:") || adminUrl.startsWith("http://localhost:")) {
            "Only a loopback gateway URL is allowed"
        }
        require(token.length >= 24) { "Gateway token is too short" }
        preferences.edit()
            .putString(KEY_URL, adminUrl.ensureTrailingSlash())
            .putString(KEY_TOKEN, encrypt(token))
            .putString(KEY_CLIENT, clientId)
            .apply()
    }

    fun clear() = preferences.edit().clear().apply()

    fun currentOrNewClientId(): String = preferences.getString(KEY_CLIENT, null)
        ?: "wuxianpi-${UUID.randomUUID()}"

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        val packed = ByteBuffer.allocate(4 + cipher.iv.size + ciphertext.size)
            .putInt(cipher.iv.size)
            .put(cipher.iv)
            .put(ciphertext)
            .array()
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val packed = ByteBuffer.wrap(Base64.decode(value, Base64.NO_WRAP))
        val ivLength = packed.int
        require(ivLength in 12..32 && packed.remaining() > ivLength) { "Invalid encrypted token" }
        val iv = ByteArray(ivLength).also(packed::get)
        val ciphertext = ByteArray(packed.remaining()).also(packed::get)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private fun String.ensureTrailingSlash() = if (endsWith('/')) this else "$this/"

    private companion object {
        const val KEY_ALIAS = "wuxianpi.gateway.token.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_URL = "admin_url"
        const val KEY_TOKEN = "token_encrypted"
        const val KEY_CLIENT = "client_id"
    }
}
