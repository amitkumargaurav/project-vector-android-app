package com.projectvector.app.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.projectvector.app.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GoogleAuthManager @Inject constructor(@ApplicationContext private val context: Context) {
    private val credentialManager = CredentialManager.create(context)

    // TODO: Wire this into the boot/login UI and exchange the Google ID token with the backend.
    // Phase 0 session truth must come from backend, not from the raw Google credential.
    suspend fun requestGoogleIdToken(activityContext: Context): Result<String> {
        if (BuildConfig.GOOGLE_WEB_CLIENT_ID.isBlank()) {
            return Result.failure(IllegalStateException("GOOGLE_WEB_CLIENT_ID is not configured"))
        }
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID)
            .setNonce(generateNonce())
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        return runCatching {
            val result = credentialManager.getCredential(activityContext, request)
            GoogleIdTokenCredential.createFrom(result.credential.data).idToken
        }
    }

    private fun generateNonce(): String {
        val raw = UUID.randomUUID().toString()
        val bytes = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
