package dev.echoping.betteresurfing.net

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 当前 WiFi 连接事实快照：
 * - [connected] 只看 TRANSPORT_WIFI（不需要定位权限，也不需要定位开关）；
 * - [ssid]/[bssid] 尽力读取：标准模式需定位权限且系统定位开关打开，Shizuku/Root 免定位。
 */
data class WifiState(
    val connected: Boolean,
    val ssid: String? = null,
    val bssid: String? = null,
    val ssidReadable: Boolean = false,
    val ipv4: String? = null,
)

/**
 * WiFi 网络监听：连接/切换/断开事件 + SSID/BSSID/IP 读取。
 *
 * 设计要点：
 * - 「是否连着 WiFi」与「能否读到 SSID」分离——连接判定不依赖定位权限；
 * - 特权读取（cmd wifi / dumpsys）在专属工作线程执行，绝不阻塞系统回调线程；
 * - onCapabilitiesChanged 因 RSSI 变化高频触发：节流，且仅在尚未读到 SSID 时才有重读价值。
 */
class NetworkMonitor(
    context: Context,
    private val onState: (WifiState) -> Unit,
) {
    private val appCtx = context.applicationContext
    private val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "be-netwatch").apply { isDaemon = true }
    }

    @Volatile private var registered = false
    @Volatile private var last = WifiState(connected = false)
    @Volatile private var lastCapAt = 0L

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh(urgent = true)
        override fun onLost(network: Network) = refresh(urgent = true)
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            // RSSI 变化也会触发此回调：节流，作为「补读 SSID」的重试通道
            val now = SystemClock.elapsedRealtime()
            if (now - lastCapAt < CAP_RETRY_MS) return
            lastCapAt = now
            refresh(urgent = false)
        }
    }

    fun register() {
        if (registered) return
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        cm.registerNetworkCallback(req, callback)
        registered = true
        refresh(urgent = true) // 建立初始基线（未连 WiFi 时系统不会发任何回调）
    }

    fun unregister() {
        if (!registered) return
        registered = false
        try { cm.unregisterNetworkCallback(callback) } catch (_: Exception) {}
    }

    /** 手动触发一次全量刷新（定位开关/授权/工作模式变化后由 UI 调用，异步执行） */
    fun refreshNow() = refresh(urgent = true)

    /** 同步判定当前是否连着 WiFi（轻量，可在主线程调用） */
    fun hasWifiSync(): Boolean = hasWifiNetwork()

    private fun refresh(urgent: Boolean) {
        if (!registered) return
        worker.execute {
            try {
                val connected = hasWifiNetwork()
                val prev = last
                if (!connected) {
                    if (prev.connected) emit(WifiState(connected = false))
                    return@execute
                }
                // 已连接：仅在 断→连/紧急事件/尚未读到名称 时才做（可能走特权的）读取
                if (!urgent && prev.connected && prev.ssidReadable) return@execute
                val (ssid, bssid, readable) = readWifi()
                emit(WifiState(true, ssid, bssid, readable, readIpv4()))
            } catch (t: Throwable) {
                android.util.Log.w("NetworkMonitor", "refresh failed: ${t.message}")
            }
        }
    }

    private fun emit(st: WifiState) {
        last = st
        android.util.Log.d("NetworkMonitor", "state=$st")
        onState(st)
    }

    /** 是否存在 WiFi 传输的网络（不要求是默认网络，无需定位权限） */
    private fun hasWifiNetwork(): Boolean = try {
        cm.allNetworks.any { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    } catch (_: Exception) {
        false
    }

    private fun wifiNetwork(): Network? = try {
        cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    } catch (_: Exception) {
        null
    }

    /** 读 WiFi 网络的 IPv4（取自 WiFi 网络而非默认网络，避免拿成蜂窝 IP） */
    fun readIpv4(): String? = try {
        val lp = wifiNetwork()?.let { cm.getLinkProperties(it) } ?: return null
        lp.linkAddresses.asSequence()
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && !it.isAnyLocalAddress }
            ?.hostAddress
    } catch (_: Exception) {
        null
    }

    /**
     * 读当前 SSID/BSSID。优先标准途径（WifiManager，需定位权限）；
     * 失败且工作模式为 Shizuku/Root 时走特权命令免定位读取。
     * 只能在工作线程调用（Shizuku bindUserService / su 均可能阻塞）。
     * @return Triple<SSID原始值, BSSID, 是否读到了有效SSID>
     */
    fun readWifi(): Triple<String?, String?, Boolean> {
        readWifiStandard().let { std -> if (std.third) return std }
        // 标准途径失败：特权模式免定位重试（Shizuku / Root）
        val mode = dev.echoping.betteresurfing.store.Prefs.workMode
        val r = when (mode) {
            "shizuku" -> dev.echoping.betteresurfing.privilege.Privilege.readWifiViaShizuku()
            "root" -> dev.echoping.betteresurfing.privilege.Privilege.readWifiViaRoot()
            else -> Triple(null, null, false)
        }
        if (r.third) {
            android.util.Log.d("NetworkMonitor", "[$mode] ssid=${r.first}")
            return r
        }
        return Triple(null, null, false)
    }

    private fun readWifiStandard(): Triple<String?, String?, Boolean> {
        val fineGranted = ContextCompat.checkSelfPermission(appCtx, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (!fineGranted) return Triple(null, null, false)
        return try {
            val wm = appCtx.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val info = wm.connectionInfo
            val ssid = info?.ssid
            val bssid = info?.bssid
            val ok = ssid != null && ssid != "<unknown ssid>" && ssid.isNotEmpty() && ssid != "0x"
            Triple(ssid, bssid?.takeIf { it != "02:00:00:00:00:00" }, ok)
        } catch (e: Exception) {
            Triple(null, null, false)
        }
    }

    companion object {
        private const val CAP_RETRY_MS = 3000L
    }
}

fun sanitizeSsid(ssid: String?): String? {
    val s = ssid?.removeSurrounding("\"")?.trim() ?: return null
    if (s.isEmpty() || s == "<unknown ssid>" || s == "0x") return null
    return s
}
