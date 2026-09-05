package dev.echoping.betteresurfing.privilege

import android.content.ComponentName
import android.content.Context
import rikka.shizuku.Shizuku
import dev.echoping.betteresurfing.shizuku.IShellService
import dev.echoping.betteresurfing.shizuku.ShellService

/**
 * 工作模式（FR-3）：
 * - STANDARD：标准权限，读 SSID 需定位；
 * - SHIZUKU：Binder 检测运行态 + 授权态；已授权时**免定位读 SSID/BSSID**（dumpsys wifi，shell uid 2000）；
 * - ROOT：su 可用时免定位读 SSID，并预留提活命令通道。
 */
enum class Mode(val id: String, val label: String) {
    STANDARD("standard", "标准模式"),
    SHIZUKU("shizuku", "Shizuku"),
    ROOT("root", "Root");

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: STANDARD
    }
}

object Privilege {

    /** Shizuku 请求授权用的 requestCode */
    const val SHIZUKU_REQ_CODE = 20091

    data class Status(
        val rootAvailable: Boolean,
        val shizukuInstalled: Boolean,
        val shizukuRunning: Boolean,   // binder 活着（Shizuku 服务端已启动）
        val shizukuGranted: Boolean,   // 已授权（或 Sui 免授权）
    ) {
        val shizukuReady: Boolean get() = shizukuRunning && shizukuGranted
    }

    @Volatile private var cached: Status? = null

    /** 探测特权可用性（结果缓存；force=true 重新探测） */
    fun detect(context: Context, force: Boolean = false): Status {
        cached?.let { if (!force) return it }
        val st = Status(
            rootAvailable = checkRoot(),
            shizukuInstalled = isPackageInstalled(context, "moe.shizuku.privileged.api") || suiInstalled(context),
            shizukuRunning = shizukuPing(),
            shizukuGranted = shizukuGranted(),
        )
        cached = st
        return st
    }

    fun invalidate() { cached = null }

    fun modeReady(mode: Mode, st: Status): Boolean = when (mode) {
        Mode.STANDARD -> true
        Mode.SHIZUKU -> st.shizukuReady
        Mode.ROOT -> st.rootAvailable
    }

    // ---------- Shizuku ----------

    private fun shizukuPing(): Boolean = try {
        Shizuku.pingBinder()
    } catch (t: Throwable) {
        false
    }

    private fun shizukuGranted(): Boolean = try {
        if (!shizukuPing()) false
        else if (Shizuku.isPreV11()) true // 旧版本无需授权
        else Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    /** Binder 到达/死亡时调用（由 Application 的监听器驱动）：失效缓存 */
    fun onBinderEvent(arrived: Boolean) {
        invalidate()
        if (arrived) {
            try { Shizuku.checkSelfPermission() } catch (_: Throwable) {}
        }
    }

    /**
     * 请求 Shizuku 授权（弹 Shizuku 的授权框）；结果经 OnRequestPermissionResultListener 回调。
     * 按 Shizuku-API 规范：binder 未就绪时调用会抛 IllegalStateException，必须先 ping；
     * 「拒绝且不再询问」时 shouldShowRequestPermissionRationale 为 true，需引导用户去 Shizuku 应用手动授权。
     */
    fun requestShizukuPermission(): Boolean {
        return try {
            if (Shizuku.isPreV11()) {
                dev.echoping.betteresurfing.engine.Repo.onLog(3, "Shizuku pre-v11 版本过旧，不支持")
                false
            } else if (!shizukuPing()) {
                dev.echoping.betteresurfing.engine.Repo.onLog(2, "Shizuku 未运行：请打开 Shizuku 应用启动服务（无线调试/root）")
                false
            } else if (shizukuGranted()) {
                true
            } else if (Shizuku.shouldShowRequestPermissionRationale()) {
                dev.echoping.betteresurfing.engine.Repo.onLog(2, "授权曾被拒绝且不再询问：请在 Shizuku 应用内手动授权本应用")
                false
            } else {
                dev.echoping.betteresurfing.engine.Repo.onLog(1, "向 Shizuku 请求授权…请在弹窗中允许")
                Shizuku.requestPermission(SHIZUKU_REQ_CODE)
                false // 结果异步回调
            }
        } catch (t: Throwable) {
            dev.echoping.betteresurfing.engine.Repo.onLog(3, "Shizuku 请求授权失败: ${t.message}")
            false
        }
    }

    /** 注册授权结果监听（Application onCreate 时调用） */
    fun addShizukuListener(l: Shizuku.OnRequestPermissionResultListener) {
        try { Shizuku.addRequestPermissionResultListener(l) } catch (_: Throwable) {}
    }

    fun removeShizukuListener(l: Shizuku.OnRequestPermissionResultListener) {
        try { Shizuku.removeRequestPermissionResultListener(l) } catch (_: Throwable) {}
    }

    @Volatile private var shellService: IShellService? = null

    /**
     * Shizuku shell（uid 2000）执行命令，返回 (exitOk, 输出)。
     * 通过 UserService（独立 shell 进程）执行，首次调用会拉起服务进程。
     * binder 失效（服务进程死亡/被冻结，如 MIUI 缓存冻结残留 daemon）时：
     * 清本地缓存 + remove 服务端记录（约定 destroy 事务会杀掉旧进程），重建重试一次。
     */
    @Synchronized
    fun runAsShizuku(cmd: String): Pair<Boolean, String> {
        if (!shizukuPing()) return Pair(false, "Shizuku 未运行")
        var lastErr = "error"
        repeat(2) { attempt ->
            try {
                val svc = shellService ?: bindShellService().also { shellService = it }
                val raw = svc.exec(cmd)
                val m = Regex("^###EXIT:(-?[0-9]+)###\\n?").find(raw)
                    ?: return Pair(false, "bad response: ${raw.take(80)}")
                return Pair(m.groupValues[1].toInt() == 0, raw.substring(m.value.length).trim())
            } catch (t: Throwable) {
                shellService = null // 服务进程可能已被杀/冻结，下次重绑
                pendingBinder = null
                lastErr = t.message ?: "error"
                appContext?.let { ctx ->
                    try { Shizuku.unbindUserService(shellServiceArgs(ctx), null, true) } catch (_: Throwable) {}
                }
            }
        }
        return Pair(false, lastErr)
    }

    /** AIDL/服务逻辑变更时+1：Shizuku 服务端按 version 重建 UserService 进程（否则复用残留旧进程） */
    private const val SHELL_SERVICE_VERSION = 3

    private fun shellServiceArgs(ctx: Context) =
        Shizuku.UserServiceArgs(ComponentName(ctx, ShellService::class.java))
            .version(SHELL_SERVICE_VERSION)
            .processNameSuffix("shell")
            .debuggable(false)

    @Volatile private var pendingBinder: android.os.IBinder? = null

    /**
     * 绑定 UserService 并同步等待返回。
     *
     * 绝不能在主线程调用：Shizuku-API 的 connected() 通过 MAIN_HANDLER.post 投递
     * onServiceConnected，主线程 park 在 latch.await 会形成死锁，必现 ANR。
     * 这里显式守护：主线程调用直接抛错（快速失败），由调用方改到后台线程。
     */
    private fun bindShellService(): IShellService {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            throw IllegalStateException("bindShellService must not run on main thread (ANR deadlock)")
        }
        pendingBinder = null // 清掉上一次超时后才迟到的陈旧回调
        val ctx = appContext ?: throw IllegalStateException("app context not ready")
        val args = shellServiceArgs(ctx)
        val latch = java.util.concurrent.CountDownLatch(1)
        val conn = object : android.content.ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: android.os.IBinder?) {
                pendingBinder = service
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                shellService = null
            }
        }
        try {
            Shizuku.bindUserService(args, conn)
            if (!latch.await(8, java.util.concurrent.TimeUnit.SECONDS)) {
                throw IllegalStateException("bind shell service timeout")
            }
        } catch (t: Throwable) {
            // 失败时解绑清理，避免 ServiceConnection 在 Shizuku 连接表里累积
            try { Shizuku.unbindUserService(args, conn, false) } catch (_: Throwable) {}
            throw t
        }
        val b = pendingBinder ?: throw IllegalStateException("null binder")
        return IShellService.Stub.asInterface(b)
    }

    /** Application context（BeApplication.onCreate 时注入） */
    @Volatile var appContext: Context? = null

    /**
     * Shizuku 免定位读当前 WiFi：优先 `cmd wifi status`（输出 ~1.5KB），
     * 不支持时回退 dumpsys（全量 >5MB 超 binder 上限，必须管道过滤）。
     * @return Triple<SSID, BSSID, 是否成功>
     */
    fun readWifiViaShizuku(): Triple<String?, String?, Boolean> {
        val s = runAsShizuku("cmd wifi status")
        if (s.first) parseDumpsysWifi(s.second).let { if (it.third) return it }
        val (ok, out) = runAsShizuku("dumpsys wifi | grep mWifiInfo")
        if (!ok || out.isEmpty()) return Triple(null, null, false)
        return parseDumpsysWifi(out)
    }

    // ---------- Root ----------

    /**
     * Root 免定位读当前 WiFi：优先 `cmd wifi status`，回退 dumpsys。
     * @return Triple<SSID, BSSID, 是否成功>
     */
    fun readWifiViaRoot(): Triple<String?, String?, Boolean> {
        val s = runAsRoot("cmd wifi status")
        if (s.first) parseDumpsysWifi(s.second).let { if (it.third) return it }
        val r = runAsRoot("dumpsys wifi")
        if (!r.first || r.second.isEmpty()) return Triple(null, null, false)
        return parseDumpsysWifi(r.second)
    }

    /** 解析 dumpsys wifi 输出中的 WifiInfo 行 */
    private fun parseDumpsysWifi(out: String): Triple<String?, String?, Boolean> {
        val re = Regex("SSID:\\s*\"([^\"]*)\"[^\\r\\n]*?BSSID:\\s*([0-9a-fA-F:]{17})")
        for (line in out.lineSequence()) {
            if (!line.contains("mWifiInfo") && !line.contains("WifiInfo")) continue
            val m = re.find(line) ?: continue
            val ssid = m.groupValues[1]
            val bssid = m.groupValues[2]
            if (ssid.isNotBlank() && ssid != "<unknown ssid>") {
                return Triple(ssid, bssid.takeIf { it != "02:00:00:00:00:00" }, true)
            }
        }
        return Triple(null, null, false)
    }

    /**
     * 以 root 执行命令；返回 (是否成功, 输出)。调用方负责审计记录。
     * 双线程排水 stdout/stderr 防止管道缓冲满死锁；su 长时间未退出（授权弹窗挂起）按超时处理。
     */
    fun runAsRoot(cmd: String, timeoutSec: Long = 10): Pair<Boolean, String> {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val out = StringBuilder()
            val err = StringBuilder()
            val tOut = kotlin.concurrent.thread { p.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }
            val tErr = kotlin.concurrent.thread { p.errorStream.bufferedReader().forEachLine { err.appendLine(it) } }
            if (!p.waitFor(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly()
                tOut.join(2000); tErr.join(2000)
                return Pair(false, "su 执行超时")
            }
            tOut.join(2000); tErr.join(2000)
            val text = (out.toString() + err.toString()).trim()
            Pair(p.exitValue() == 0, text)
        } catch (e: Exception) {
            Pair(false, e.message ?: "error")
        }
    }

    // ---------- 已保存 WiFi 列表 ----------

    /**
     * 读取本机已保存的 WiFi 网络（SSID 去重列表）。
     * 标准模式：Android 10+ 起系统禁止普通应用读取已保存网络，返回 (false, emptyList())。
     * Shizuku/Root：`cmd wifi list-networks`（shell/root 专属）；Root 失败时回退 WifiConfigStore.xml。
     * @return Pair<是否可读, SSID 列表>；可读但本机无已保存网络时返回 (true, emptyList())
     */
    fun readSavedWifi(): Pair<Boolean, List<String>> {
        return when (dev.echoping.betteresurfing.store.Prefs.workMode) {
            "shizuku" -> readSavedWifiVia(::runAsShizuku, fileFallback = false)
            "root" -> readSavedWifiVia(::runAsRoot, fileFallback = true)
            else -> Pair(false, emptyList())
        }
    }

    private fun readSavedWifiVia(exec: (String) -> Pair<Boolean, String>, fileFallback: Boolean): Pair<Boolean, List<String>> {
        val (ok, out) = exec("cmd wifi list-networks")
        if (ok && !out.contains("Unknown command")) {
            val list = parseSavedNetworks(out)
            if (list.isEmpty()) {
                dev.echoping.betteresurfing.engine.Repo.onLog(2, "list-networks 未解析出 SSID: ${out.take(120)}")
            }
            return Pair(true, list)
        }
        dev.echoping.betteresurfing.engine.Repo.onLog(2, "cmd wifi list-networks 失败: ${out.take(120)}")
        if (!fileFallback) return Pair(false, emptyList())
        // Root 回退：直接读 WiFi 配置存储（A16+ 移至 apexdata 目录，两个路径都试）
        val (ok2, xml) = exec("cat /data/misc/apexdata/com.android.wifi/WifiConfigStore.xml /data/misc/wifi/WifiConfigStore.xml 2>/dev/null")
        if (!ok2 || xml.isBlank()) {
            dev.echoping.betteresurfing.engine.Repo.onLog(2, "读取 WifiConfigStore.xml 失败: ${xml.take(80)}")
            return Pair(false, emptyList())
        }
        val list = Regex("<string name=\"SSID\">\"([^\"]*)\"</string>").findAll(xml)
            .map { it.groupValues[1] }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
        return Pair(true, list)
    }

    /**
     * 解析 `cmd wifi list-networks` 表格输出，跳过表头。
     * 旧版 Android：SSID 带双引号；新版（Android 15+）：无定界符定宽表格，
     * 末列为 Security type（wpa2-psk / wpa3-sae^ / open / owe^ 等），同一网络按安全类型多行重复。
     */
    private fun parseSavedNetworks(out: String): List<String> {
        val quoted = Regex("\"([^\"]*)\"")
        val lines = out.lineSequence().filter { it.isNotBlank() }.toList()
        // 策略 A：SSID 带引号（旧版格式，表头无引号不会误入）
        val quotedOut = lines
            .flatMap { quoted.findAll(it) }
            .map { it.groupValues[1] }
            .filter { it.isNotBlank() && it != "<unknown ssid>" }
            .distinct()
        if (quotedOut.isNotEmpty()) return quotedOut
        // 策略 B：定宽表格。数据行以纯数字 Id 开头；有 Security 列时去掉末列，中间段即 SSID（可含空格）
        val header = lines.firstOrNull { !it.trimStart().startsWithDigit() && it.contains("SSID") }
        val hasSecurityCol = header?.contains("Security") == true
        val leadId = Regex("^\\s*\\d+\\s+")
        val result = mutableListOf<String>()
        for (line in lines) {
            val m = leadId.find(line) ?: continue
            var body = line.trimEnd().substring(m.range.last + 1)
            if (hasSecurityCol) {
                val cut = body.lastIndexOfAny(charArrayOf(' ', '\t'))
                if (cut > 0) body = body.substring(0, cut).trimEnd()
            }
            if (body.isNotBlank() && body != "<unknown ssid>") result.add(body)
        }
        return result.distinct()
    }

    private fun String.startsWithDigit(): Boolean = firstOrNull()?.isDigit() == true

    // ---------- 特权保活与隐身 ----------

    /** 保活加固命令清单（Shizuku shell uid 即可执行，全部幂等） */
    private fun hardenCmds(pkg: String) = listOf(
        // Doze/电池优化白名单：系统休眠时不限制网络与作业
        "dumpsys deviceidle whitelist +$pkg",
        // MIUI/国产 ROM 后台运行权限
        "cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow",
        // Android 12+ 未使用应用休眠豁免（部分 ROM 无此 op，失败可忽略）
        "cmd appops set $pkg SYSTEM_EXEMPT_FROM_HIBERNATION allow",
    )

    /**
     * 执行保活加固（当前工作模式为 Shizuku/Root 时）。
     * @return (命令, 是否成功, 输出) 列表，调用方逐条记入日志
     */
    fun hardenKeepAlive(ctx: Context): List<Triple<String, Boolean, String>> {
        val pkg = ctx.packageName
        val mode = dev.echoping.betteresurfing.store.Prefs.workMode
        return hardenCmds(pkg).map { c ->
            val r = when (mode) {
                "shizuku" -> runAsShizuku(c)
                "root" -> runAsRoot(c)
                else -> Pair(false, "需要 Shizuku/Root 模式")
            }
            Triple(c, r.first, r.second)
        }
    }

    // ---------- 包检测 ----------

    private fun isPackageInstalled(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, 0) != null
    } catch (e: Exception) {
        false
    }

    private fun suiInstalled(ctx: Context): Boolean = try {
        isPackageInstalled(ctx, "rikka.sui")
    } catch (e: Exception) {
        false
    }

    private fun checkRoot(): Boolean = try {
        val p = Runtime.getRuntime().exec("su -c id")
        // detect() 可能在组合（主线程）中同步调用：su 弹授权挂起时必须有界，超时即视为不可用
        if (p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.exitValue() == 0
        else { p.destroyForcibly(); false }
    } catch (e: Exception) {
        false
    }
}
