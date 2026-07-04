package com.projectvector.app.notifications

import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class FcmTokenProvider @Inject constructor() {
    suspend fun getToken(): Result<String> = suspendCancellableCoroutine { continuation ->
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token -> continuation.resume(Result.success(token)) }
            .addOnFailureListener { error -> continuation.resume(Result.failure(error)) }
    }
}
