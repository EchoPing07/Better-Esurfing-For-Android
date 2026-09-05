package dev.echoping.betteresurfing.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.echoping.be.mobile.Mobile
import dev.echoping.betteresurfing.MainActivity
import dev.echoping.betteresurfing.R
import dev.echoping.betteresurfing.engine.EngineHolder
import dev.echoping.betteresurfing.engine.Repo
import dev.echoping.betteresurfing.engine.UiState
import dev.echoping.betteresurfing.net.NetWatch
import dev.echoping.betteresurfing.net.WifiState
import dev.echoping.betteresurfing.net.sanitizeSsid
import dev.echoping.betteresurfing.privilege.Mode
import dev.echoping.betteresurfing.privilege.Privilege
import dev.echoping.betteresurfing.store.Prefs
import dev.echoping.betteresurfing.ui.AuthStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 认证前台服务（dataSync）：持有引擎生命周期，常驻通知显示状态并提供登录/登出快捷操作。
 */
class AuthService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    companion object {
        const val CHANNEL_ID = "auth_status"
        const val NOTI_ID = 1001

        const val ACTION_START = "dev.echoping.be.START"        // 启动并以当前默认账号认证
        const val ACTION_LOGIN_NOW = "dev.echoping.be.LOGIN_NOW" // 立即用指定索引账号重登
        const val ACTION_LOGOUT = "dev.echoping.be.LOGOUT"
        const val ACTION_STOP = "dev.echoping.be.STOP"
        const val EXTRA_INDEX = "index"

        @Volatile var isRunning = false; private set

        /** 响应式运行态：UI（启动/停止 FAB）收集此流刷新 */
        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        fun start(ctx: Context) {
            val i = Intent(ctx, AuthService::class.java).setAction(ACTION_START)
            ctx.startForegroundService(i)
        }

        fun loginNow(ctx: Context, index: Int) {
            val i = Intent(ctx, AuthService::class.java).setAction(ACTION_LOGIN_NOW).putExtra(EXTRA_INDEX, index)
            ctx.startForegroundService(i)
        }

        fun logout(ctx: Context) {
            ctx.startService(Intent(ctx, AuthService::class.java).setAction(ACTION_LOGOUT))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, AuthService::class.java).setAction(ACTION_STOP))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        createChannel()
        // 常驻通知跟随引擎状态（只在 onCreate 挂一次，避免每次 onStartCommand 重复收集）
        scope.launch { Repo.state.collect { updateNotification(it) } }
        // NetWatch（Application 已启动）驱动：WiFi 断开停引擎、连上按规则认证
        scope.launch { NetWatch.state.collect { handleWifi(it) } }
        maybeHarden()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isRunning = true
        _running.value = true
        startForegroundCompat(buildNotification(Repo.state.value))

        when (intent?.action) {
            ACTION_START -> {
                // 运行记忆跟随服务生命周期而非引擎状态：未连 WiFi 启动同样记为运行中
                Prefs.persistServiceRunning(true)
                authWith(Prefs.activeIndex)
            }
            ACTION_LOGIN_NOW -> {
                Prefs.persistServiceRunning(true)
                authWith(intent.getIntExtra(EXTRA_INDEX, Prefs.activeIndex))
            }
            ACTION_LOGOUT -> EngineHolder.getOrCreate().logout()
            ACTION_STOP -> {
                EngineHolder.engine?.stop()
                shutdown()
                return START_NOT_STICKY
            }
            null -> {
                // 服务被系统重启（START_STICKY）：有运行记忆才恢复，否则自行退场
                // （「记住运行状态」关闭时 lastRunning 恒为 false，重启即停）
                if (!Prefs.lastRunning) {
                    shutdown()
                    return START_NOT_STICKY
                }
                if (Repo.state.value.engineState == 0) authWith(Prefs.activeIndex)
            }
        }
        return START_STICKY
    }

    private fun authWith(index: Int) {
        // WiFi 门槛：未连接无线网络时不启动引擎，连接后由 NetWatch 事件自动触发
        if (!NetWatch.isConnectedNow()) {
            Repo.onLog(2, "未连接无线网络，暂不认证；连接 WiFi 后自动开始")
            return
        }
        val acc = Prefs.accountAt(index)
        if (acc == null) {
            Repo.onLog(3, "没有可用账号，请先在「账号」页添加")
            return
        }
        Prefs.setActive(index)
        val e = EngineHolder.getOrCreate()
        EngineHolder.currentAccount = acc
        try {
            e.loginNow(EngineHolder.toGo(acc))
        } catch (ex: Exception) {
            Repo.onLog(3, "启动认证失败: ${ex.message}")
        }
    }

    /** WiFi 状态 → 规则判定（FR-2）。连接判定与 SSID 可读性分离：连着但读不到名时按降级策略 */
    private fun handleWifi(st: WifiState) {
        if (!st.connected) {
            if (EngineHolder.engine?.isRunning == true) {
                Repo.onLog(1, "未连接无线网络，暂停认证引擎")
                EngineHolder.engine?.stop()
            }
            return
        }
        val allow = Prefs.shouldAuth(st.ssid, st.bssid, st.ssidReadable)
        Repo.onLog(1, "WiFi 事件 ssid=${sanitizeSsid(st.ssid) ?: "未知"} 触发判定=$allow")
        if (allow) {
            when (Repo.state.value.engineState) {
                // 已在线 / 认证流程进行中：避免重复触发重登
                Mobile.StateOnline, Mobile.StateAuthorizing, Mobile.StateDetecting -> Unit
                else -> authWith(Prefs.activeIndex)
            }
        } else {
            // 黑名单命中或白名单未命中且不允许降级：保持静默（不发任何探测包由引擎停止保证）
            if (Prefs.ruleMode == dev.echoping.betteresurfing.store.RuleMode.BLACKLIST ||
                !Prefs.ssidFallbackAuth
            ) EngineHolder.engine?.stop()
        }
    }

    /** 特权模式自动保活加固（doze 白名单 + 后台运行 appops，幂等）；逐条结果写入日志 */
    private fun maybeHarden() {
        val mode = Mode.fromId(Prefs.workMode)
        if (mode == Mode.STANDARD || !Prefs.autoHarden) return
        scope.launch(Dispatchers.IO) {
            val st = Privilege.detect(this@AuthService, force = true)
            val ready = (mode == Mode.ROOT && st.rootAvailable) || (mode == Mode.SHIZUKU && st.shizukuReady)
            if (!ready) return@launch
            for ((cmd, ok, out) in Privilege.hardenKeepAlive(this@AuthService)) {
                Repo.onLog(if (ok) 1 else 2, "保活加固 $cmd → " + if (ok) "成功" else "失败: ${out.take(60)}")
            }
        }
    }

    private fun shutdown() {
        Prefs.persistServiceRunning(false)
        isRunning = false
        _running.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ---- 通知 ----

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "校园网认证状态", NotificationManager.IMPORTANCE_LOW).apply {
                description = "认证引擎运行状态与快捷操作"
            }
        )
    }

    private fun buildNotification(state: UiState): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val reauthPi = PendingIntent.getService(
            this, 1, Intent(this, AuthService::class.java).setAction(ACTION_LOGIN_NOW).putExtra(EXTRA_INDEX, Prefs.activeIndex),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopPi = PendingIntent.getService(
            this, 2, Intent(this, AuthService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // 与首页状态条同源：主词作标题、副行作正文，不再两套拼法，账号脱敏后上锁屏
        val p = AuthStatus.presentation(state, isRunning)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_auth)
            .setContentTitle(p.label)
            .setContentText(p.detail)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setShowWhen(false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .addAction(0, getString(R.string.action_reauth), reauthPi)
            .addAction(0, getString(R.string.action_stop), stopPi)
            .build()
    }

    @Volatile private var lastNotiKey = ""
    private fun updateNotification(state: UiState) {
        val p = AuthStatus.presentation(state, isRunning)
        val key = p.label + "|" + p.detail
        if (key == lastNotiKey) return
        lastNotiKey = key
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTI_ID, buildNotification(state))
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTI_ID, n)
        }
    }
}
