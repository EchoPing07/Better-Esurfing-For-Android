package dev.echoping.betteresurfing.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.echoping.betteresurfing.service.AuthService
import dev.echoping.betteresurfing.store.Prefs

/**
 * 开机自启（FR-4.1）：按上次运行状态恢复认证服务。
 * 从 BOOT_COMPLETED 启动前台服务在 Android 12+ 上属于允许场景。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return

        Prefs.init(context)
        if (!Prefs.autoBoot) return
        if (!Prefs.lastRunning) return
        if (Prefs.activeAccount() == null) return

        try {
            AuthService.start(context)
        } catch (e: Exception) {
            android.util.Log.w("BootReceiver", "start service failed: ${e.message}")
        }
    }
}
