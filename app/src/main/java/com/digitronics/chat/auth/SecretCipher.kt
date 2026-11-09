package com.digitronics.chat.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Seals small secrets (the refresh token, the pending PKCE verifier) before they touch disk. */
interface SecretCipher {
    fun encrypt(plain: String): String

    /** Null when the blob cannot be opened (key gone, tampered); callers treat that as "no secret". */
    fun decrypt(sealed: String): String?
}

/**
 * AES-256-GCM with a non-exportable key in the Android Keystore. The key never
 * leaves secure hardware where the device has it; backups are disabled, so a
 * restored copy of the ciphertext without the key is useless anyway.
 *
 * Blob format (base64): version byte 1 | IV length | IV | ciphertext+tag.
 */
class KeystoreCipher(private val alias: String = DEFAULT_ALIAS) : SecretCipher {

    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val out = ByteArray(2 + iv.size + body.size)
        out[0] = VERSION
        out[1] = iv.size.toByte()
        System.arraycopy(iv, 0, out, 2, iv.size)
        System.arraycopy(body, 0, out, 2 + iv.size, body.size)
        return Base64.getEncoder().encodeToString(out)
    }

    override fun decrypt(sealed: String): String? = runCatching {
        val raw = Base64.getDecoder().decode(sealed)
        require(raw.size > 2 && raw[0] == VERSION)
        val ivLength = raw[1].toInt()
        require(ivLength in 12..16 && raw.size > 2 + ivLength)
        val iv = raw.copyOfRange(2, 2 + ivLength)
        val body = raw.copyOfRange(2 + ivLength, raw.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        String(cipher.doFinal(body), Charsets.UTF_8)
    }.getOrNull()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val DEFAULT_ALIAS = "digitronics_chat_secrets_v1"
        const val TAG_BITS = 128
        const val VERSION: Byte = 1
    }
}
