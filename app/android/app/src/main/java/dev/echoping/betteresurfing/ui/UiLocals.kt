package dev.echoping.betteresurfing.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import dev.echoping.betteresurfing.store.Prefs

val LocalSnackbar = staticCompositionLocalOf<SnackbarHostState> {
    error("LocalSnackbar not provided")
}

/** 交互开关：Compose 可观察，写入即持久化到 Prefs。 */
object UiToggles {
    private var _predictiveBack by mutableStateOf(false)
    val predictiveBack: Boolean get() = _predictiveBack

    private var loaded = false

    /** 从 Prefs 恢复（Prefs.init 之后调用）。 */
    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        _predictiveBack = Prefs.predictiveBack
    }

    fun setPredictiveBack(v: Boolean) {
        if (predictiveBack == v) return
        _predictiveBack = v
        Prefs.setPredictiveBack(v)
    }
}
