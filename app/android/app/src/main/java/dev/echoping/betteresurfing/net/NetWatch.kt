package dev.echoping.betteresurfing.net

import android.content.Context
import dev.echoping.betteresurfing.engine.Repo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 进程级 WiFi 事实源：由 Application 启动，常驻一个 NetworkMonitor。
 * UI（Repo 状态）与认证服务（AuthService）共享同一份数据——
 * 服务未运行时首页也能实时显示「未连接无线网络」。
 */
object NetWatch {

    private val _state = MutableStateFlow(WifiState(connected = false))
    val state: StateFlow<WifiState> = _state.asStateFlow()

    private var monitor: NetworkMonitor? = null

    @Synchronized
    fun start(ctx: Context) {
        if (monitor != null) return
        val m = NetworkMonitor(ctx) { st ->
            _state.value = st
            Repo.onWifi(st)
        }
        m.register()
        monitor = m
    }

    /** 手动触发全量刷新（onResume / 授权 / 定位开关变化后调用，异步执行） */
    fun refresh() = monitor?.refreshNow()

    /** 同步判定当前是否连着 WiFi（轻量，可在主线程调用） */
    fun isConnectedNow(): Boolean = monitor?.hasWifiSync() ?: false
}
