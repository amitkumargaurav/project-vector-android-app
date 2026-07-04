package com.projectvector.app.webview

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackPressController @Inject constructor() {
    private val _mode = MutableStateFlow(BackPressMode.DEFAULT)
    val mode: StateFlow<BackPressMode> = _mode

    fun setMode(mode: BackPressMode) {
        _mode.value = mode
    }
}

enum class BackPressMode { DEFAULT, CONFIRM_EXIT, DISABLED }
