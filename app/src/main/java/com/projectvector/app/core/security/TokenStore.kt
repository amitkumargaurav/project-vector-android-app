package com.projectvector.app.core.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TokenStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "vector_secure_session",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun storeSession(session: AuthSession) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, session.accessToken)
            .putString(KEY_REFRESH_TOKEN, session.refreshToken)
            .putString(KEY_ID_TOKEN, session.idToken)
            .putLong(KEY_EXPIRES_AT_EPOCH_SECONDS, session.expiresAtEpochSeconds ?: 0L)
            .apply()
    }

    fun storeAccessToken(token: String) {
        storeSession(AuthSession(accessToken = token))
    }

    fun storeSession(accessToken: String, refreshToken: String?, expiresAt: String?, userId: String?) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putString(KEY_EXPIRES_AT, expiresAt)
            .putString(KEY_USER_ID, userId)
            .apply()
    }

    fun getAccessToken(): String? = prefs.getString(KEY_ACCESS_TOKEN, null)

    fun getSession(): Map<String, String?> = mapOf(
        KEY_ACCESS_TOKEN to prefs.getString(KEY_ACCESS_TOKEN, null),
        KEY_REFRESH_TOKEN to prefs.getString(KEY_REFRESH_TOKEN, null),
        KEY_EXPIRES_AT to prefs.getString(KEY_EXPIRES_AT, null),
        KEY_USER_ID to prefs.getString(KEY_USER_ID, null),
    )

    fun hasSession(): Boolean = !getAccessToken().isNullOrBlank()

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_ID_TOKEN = "id_token"
        const val KEY_EXPIRES_AT_EPOCH_SECONDS = "expires_at_epoch_seconds"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_EXPIRES_AT = "expires_at"
        const val KEY_USER_ID = "user_id"
    }
}

data class AuthSession(
    val accessToken: String,
    val refreshToken: String? = null,
    val idToken: String? = null,
    val expiresAtEpochSeconds: Long? = null,
)
