package com.socialtap.android

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** OAuth tokens and small account facts, kept in encrypted storage. */
class TokenStore(context: Context) {
    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "socialtap_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    val accessToken: String?
        get() = prefs.getString(KEY_ACCESS, null)

    val refreshToken: String?
        get() = prefs.getString(KEY_REFRESH, null)

    val isSignedIn: Boolean
        get() = !accessToken.isNullOrEmpty()

    var userId: Long
        get() = prefs.getLong(KEY_USER_ID, 0L)
        set(value) {
            prefs.edit().putLong(KEY_USER_ID, value).apply()
        }

    var username: String?
        get() = prefs.getString(KEY_USERNAME, null)
        set(value) {
            prefs.edit().putString(KEY_USERNAME, value).apply()
        }

    val pendingVerifier: String?
        get() = prefs.getString(KEY_PENDING_VERIFIER, null)

    val pendingState: String?
        get() = prefs.getString(KEY_PENDING_STATE, null)

    fun savePending(verifier: String, state: String) {
        prefs.edit()
            .putString(KEY_PENDING_VERIFIER, verifier)
            .putString(KEY_PENDING_STATE, state)
            .apply()
    }

    fun clearPending() {
        prefs.edit().remove(KEY_PENDING_VERIFIER).remove(KEY_PENDING_STATE).apply()
    }

    fun saveTokens(access: String, refresh: String?) {
        val editor = prefs.edit().putString(KEY_ACCESS, access)
        if (!refresh.isNullOrEmpty()) editor.putString(KEY_REFRESH, refresh)
        editor.apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_USER_ID = "user_id"
        const val KEY_USERNAME = "username"
        const val KEY_PENDING_VERIFIER = "pending_verifier"
        const val KEY_PENDING_STATE = "pending_state"
    }
}
