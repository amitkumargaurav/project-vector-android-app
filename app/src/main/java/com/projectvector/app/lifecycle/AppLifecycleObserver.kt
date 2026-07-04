package com.projectvector.app.lifecycle

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.projectvector.app.bridge.ReactCallbackSender
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppLifecycleObserver @Inject constructor(private val callbackSender: ReactCallbackSender) : DefaultLifecycleObserver {
    override fun onStart(owner: LifecycleOwner) {
        callbackSender.onAppForeground()
    }

    override fun onStop(owner: LifecycleOwner) {
        callbackSender.onAppBackground()
    }
}
