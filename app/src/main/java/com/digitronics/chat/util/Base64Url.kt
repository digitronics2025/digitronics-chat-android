package com.digitronics.chat.util

import java.security.MessageDigest
import java.util.Base64

/** RFC 4648 §5 base64url without padding — the encoding PKCE, tokens and manifest signatures use. */
object Base64Url {
    fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun decode(value: String): ByteArray = Base64.getUrlDecoder().decode(value)
}

fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

fun sha256Hex(text: String): String = sha256(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
