package com.projectvector.app.auth

import com.projectvector.app.BuildConfig
import com.projectvector.app.core.security.AuthSession
import com.projectvector.app.core.security.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val tokenStore: TokenStore,
) {
    suspend fun exchangeGoogleIdToken(idToken: String): Result<AuthSession> = withContext(Dispatchers.IO) {
        runCatching {
            if (BuildConfig.VECTOR_AUTH_EXCHANGE_URL.isBlank()) {
                error("VECTOR_AUTH_EXCHANGE_URL is not configured")
            }

            val connection = (URL(BuildConfig.VECTOR_AUTH_EXCHANGE_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 15_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(JSONObject().put("idToken", idToken).toString())
            }

            val body = if (connection.responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                error("Login failed (${connection.responseCode})${errorBody.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}")
            }

            val json = JSONObject(body)
            AuthSession(
                accessToken = json.requireString("accessToken", "access_token"),
                refreshToken = json.optNullableString("refreshToken", "refresh_token"),
                idToken = json.optNullableString("idToken", "id_token"),
                expiresAtEpochSeconds = json.optLongOrNull("expiresAtEpochSeconds", "expires_at_epoch_seconds", "expiresAt", "expires_at"),
            ).also(tokenStore::storeSession)
        }
    }

    private fun JSONObject.requireString(vararg names: String): String =
        optNullableString(*names) ?: error("Login response is missing ${names.first()}")

    private fun JSONObject.optNullableString(vararg names: String): String? = names.firstNotNullOfOrNull { name ->
        optString(name).takeIf { it.isNotBlank() }
    }

    private fun JSONObject.optLongOrNull(vararg names: String): Long? = names.firstNotNullOfOrNull { name ->
        if (has(name) && !isNull(name)) optLong(name) else null
    }
}
