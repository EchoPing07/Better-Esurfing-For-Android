package dev.echoping.betteresurfing.engine

import dev.echoping.be.mobile.Account
import dev.echoping.be.mobile.Callback
import dev.echoping.be.mobile.Config
import dev.echoping.be.mobile.Engine
import dev.echoping.be.mobile.Mobile
import dev.echoping.betteresurfing.store.Account as AppAccount
import dev.echoping.betteresurfing.net.WifiState
import dev.echoping.betteresurfing.net.sanitizeSsid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UI 可观察的引擎状态 */
data class UiState(
    val engineState: Int = Mobile.StateIdle,
    val detail: String = "未启动",
    val onlineAccount: String? = null,
    val ssid: String? = null,
    val ipv4: String? = null,
    /** 是否连着 WiFi（不需要定位权限即可判定） */
    val wifiOnline: Boolean = false,
    /** 当前 SSID 是否成功读到（连着但读不到名时 UI 提示、规则走降级策略） */
    val ssidReadable: Boolean = false,
)

data class LogLine(val ts: Long, val level: Int, val msg: String)

/**
 * 全局状态仓库：gomobile 回调（Go 线程）写入，Compose 收集。
 */
object Repo {
    private const val MAX_LOGS = 500

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    private val _logs = ArrayList<LogLine>(MAX_LOGS)
    private val _logsFlow = MutableStateFlow<List<LogLine>>(emptyList())
    val logs = _logsFlow.asStateFlow()

    @Volatile var lastOnlineAt: Long = 0L

    /** Shizuku Binder 事件纪元：到达/死亡时 +1，UI 据此刷新特权状态 */
    private val _shizukuEpoch = MutableStateFlow(0L)
    val shizukuEpoch = _shizukuEpoch.asStateFlow()

    fun notifyShizukuBinder(arrived: Boolean) {
        _shizukuEpoch.value += 1
        onLog(if (arrived) 1 else 2, if (arrived) "已连接 Shizuku 服务" else "Shizuku 服务已断开")
    }

    fun onStateChanged(s: Int, detail: String) {
        val prev = _state.value
        val account = if (s == Mobile.StateOnline) EngineHolder.currentAccount?.username else null
        if (s == Mobile.StateOnline) lastOnlineAt = System.currentTimeMillis()
        val keep = prev.onlineAccount?.takeIf { s != Mobile.StateLoggedOut && s != Mobile.StateIdle }
        _state.value = prev.copy(
            engineState = s,
            detail = detail,
            onlineAccount = account ?: keep,
        )
        // 运行记忆（lastRunning）由 AuthService 按服务生命周期写入，不再跟随引擎认证状态：
        // 未连 WiFi 时引擎保持 Idle，但服务应被视为运行中
    }

    fun onWifi(st: WifiState) {
        val prev = _state.value
        val ssid = sanitizeSsid(st.ssid)
        if (prev.ssid == ssid && prev.ipv4 == st.ipv4 &&
            prev.wifiOnline == st.connected && prev.ssidReadable == st.ssidReadable
        ) return
        _state.value = prev.copy(
            ssid = ssid,
            ipv4 = st.ipv4,
            wifiOnline = st.connected,
            ssidReadable = st.ssidReadable,
        )
    }

    fun onLog(level: Int, message: String) {
        android.util.Log.println(
            when (level) { 3 -> android.util.Log.ERROR; 2 -> android.util.Log.WARN; 1 -> android.util.Log.INFO; else -> android.util.Log.DEBUG },
            "BetterEsurfing", message
        )
        synchronized(_logs) {
            if (_logs.size >= MAX_LOGS) _logs.removeAt(0)
            _logs.add(LogLine(System.currentTimeMillis(), level, message))
            _logsFlow.value = _logs.toList()
        }
    }

    fun clearLogs() {
        synchronized(_logs) {
            _logs.clear()
            _logsFlow.value = emptyList()
        }
    }

    /** 导出日志（已由引擎侧脱敏） */
    fun dumpLogs(): String = synchronized(_logs) {
        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)
        buildString {
            for (l in _logs) {
                append(fmt.format(l.ts)).append(' ').append(levelChar(l.level)).append(' ').append(l.msg).append('\n')
            }
        }
    }

    private fun levelChar(l: Int) = when (l) {
        Mobile.LogDebug -> 'D'
        Mobile.LogInfo -> 'I'
        Mobile.LogWarn -> 'W'
        else -> 'E'
    }
}

/** 引擎单例持有者（生命周期由 AuthService 控制） */
object EngineHolder {
    @Volatile var engine: Engine? = null
        private set

    @Volatile var currentAccount: AppAccount? = null

    private val lock = Any()

    fun getOrCreate(): Engine {
        synchronized(lock) {
            engine?.let { return it }
            val prefs = dev.echoping.betteresurfing.store.Prefs
            val cfg = Config().apply {
                probeURLsJSON = prefs.probeUrlsJson
                domainMapJSON = prefs.domainMapJson
                detectIntervalSec = prefs.detectIntervalSec.toLong()
                heartbeatRetry = prefs.heartbeatRetry.toLong()
                shieldWindowSec = prefs.shieldSec.toLong()
            }
            val e = try {
                Mobile.newEngine(cfg, BridgeCallback())
            } catch (ex: Exception) {
                throw IllegalStateException("引擎初始化失败: ${ex.message}", ex)
            }
            engine = e
            return e
        }
    }

    fun toGo(a: AppAccount): Account = Account().apply {
        username = a.username
        password = a.password
        userAgent = a.userAgent
        note = a.note
    }
}

/** gomobile Callback 实现：转发到 Repo（回调来自 Go goroutine，StateFlow 线程安全） */
class BridgeCallback : Callback {
    override fun onStateChanged(state: Int, detail: String) = Repo.onStateChanged(state, detail)
    override fun onLog(level: Int, message: String) = Repo.onLog(level, message)
}
