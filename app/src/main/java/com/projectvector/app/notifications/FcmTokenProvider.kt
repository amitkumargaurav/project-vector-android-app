package com.projectvector.app.notifications

import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class FcmTokenProvider @Inject constructor() {
    suspend fun getToken(): Result<String> = runCatching {
        suspendCancellableCoroutine { continuation ->
            val task = FirebaseMessaging.getInstance().token
            task.addOnSuccessListener { token ->
                if (continuation.isActive) continuation.resume(Result.success(token))
            }
            task.addOnFailureListener { error ->
                if (continuation.isActive) continuation.resume(Result.failure(error))
            }
            task.addOnCanceledListener {
                if (continuation.isActive) continuation.resume(Result.failure(CancellationException("FCM token request was cancelled")))
            }
        }.getOrThrow()
    }
}
