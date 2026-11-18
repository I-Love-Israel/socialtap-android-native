package com.socialtap.android

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/** PKCE helpers (RFC 7636, S256). */
object Pkce {
    private val random = SecureRandom()
    private val flags = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING

    /** Random URL-safe string. 48 bytes gives a 64-character verifier; 32 bytes gives a 43-character state. */
    fun randomString(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        random.nextBytes(bytes)
        return Base64.encodeToString(bytes, flags)
    }

    fun challengeFor(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, flags)
    }
}
