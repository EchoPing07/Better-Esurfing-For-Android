package dev.echoping.betteresurfing.store

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 密码加密存储：Android Keystore AES-256-GCM。
 * 密文格式：Base64(iv(12B) + ciphertext)。
 */
object Crypto {
    private const val KEY_ALIAS = "be_master_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(c.iv + ct, Base64.NO_WRAP)
    }

    fun decrypt(enc: String): String {
        if (enc.isEmpty()) return ""
        return try {
            val data = Base64.decode(enc, Base64.NO_WRAP)
            val iv = data.copyOfRange(0, 12)
            val ct = data.copyOfRange(12, data.size)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            String(c.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }
}

/**
 * 认证 UA 预设（与服务端算法池分派对应，同 Go 引擎 portal 包预设表）。
 * 服务端按 UA 家族分派算法池且按会话随机轮换：2104→新代 9、2089→旧代 9、
 * 1003→PC 6；自动模式被拒时按链回退。
 */
object UserAgents {
    /** 自动回退链（推荐） */
    const val AUTO = ""
    const val ANDROID_2104 = "CCTP/android11_64/2104" // 新代算法池（官方 App 同源）
    const val ANDROID_2089 = "CCTP/android64_vpn/2089" // 旧代算法池
    const val ANDROID_2093 = "CCTP/android64_vpn/2093" // 历史默认，最老学校兜底
    const val PC = "CCTP/Linux64/1003" // PC 通道

    /** 展示标签（列表行/下拉框收起态） */
    fun label(ua: String): String = when (ua) {
        AUTO -> "自动"
        ANDROID_2104 -> "安卓 2104"
        ANDROID_2089 -> "安卓 2089"
        ANDROID_2093 -> "安卓 2093"
        PC -> "PC 通道"
        else -> ua
    }

    /**
     * 旧版账号数据 → UA 迁移（纯函数，可单测）：
     * 已有 user_agent 原样保留；否则按旧 channel 字段映射
     * （pc → PC 通道 UA；phone → 自动链——旧默认 2093 已被服务器准入闸拒绝）。
     */
    fun migrateLegacy(rawUserAgent: String?, legacyChannel: String): String = when {
        !rawUserAgent.isNullOrEmpty() -> rawUserAgent
        legacyChannel == "pc" -> PC
        else -> AUTO
    }

    /** 自定义 UA 合法性（与 Go 引擎 ValidUserAgent 对齐）：非空、≤64、禁空白与控制字符 */
    fun isValidCustom(ua: String): Boolean =
        ua.isNotEmpty() && ua.length <= 64 &&
            ua.none { it.isWhitespace() || it.code < 0x20 || it.code == 0x7f }
}

/** 账号 */
data class Account(
    var username: String,
    var password: String,
    var userAgent: String = UserAgents.AUTO, // 认证 UA；空 = 自动回退链
    var note: String = "",
)

/** WiFi 规则条目 */
data class WifiRule(
    var type: String = "ssid", // ssid | bssid
    var value: String,
    var note: String = "",
    var enabled: Boolean = true,
)

/** 规则模式 */
enum class RuleMode { WHITELIST, BLACKLIST, ALL;

    companion object {
        fun from(s: String) = entries.firstOrNull { it.name.equals(s, true) } ?: ALL
    }
}

/**
 * 全部持久化配置。密码以 Keystore 加密；结构化数据用 JSON 存 SharedPreferences。
 */
object Prefs {
    private lateinit var sp: android.content.SharedPreferences

    // ---- 账号 ----
    private val accounts = mutableListOf<Account>()
    var activeIndex = 0; private set

    // ---- WiFi 规则 ----
    var ruleMode = RuleMode.ALL; private set
    private val rules = mutableListOf<WifiRule>()

    // ---- 设置 ----
    /** 工作模式：standard | shizuku | root */
    var workMode = "standard"; private set
    var autoBoot = true; private set
    /** 记住运行状态：开启时服务的启动/停止会被持久化，杀后台/重开应用后自动恢复；
     *  关闭后清除记忆，重开应用一律为停止状态 */
    var rememberRunning = true; private set
    /** SSID 读取失败时的策略：true=仍尝试认证（默认），false=不认证 */
    var ssidFallbackAuth = true; private set
    /** 特权模式下服务启动时自动加固保活（doze 白名单 + 后台运行 appops） */
    var autoHarden = true; private set
    /** 隐藏后台运行：离开应用从最近任务移除；特权模式下隐藏常驻通知 */
    var hideInBackground = false; private set
    /** 预测性返回手势：侧滑返回时跟随手势实时预览上一页（Android 13+ 生效） */
    var predictiveBack = false; private set
    var probeUrlsJson = ""; private set      // 空=引擎内置默认
    var domainMapJson = ""; private set      // 空=引擎内置默认（含 enet.10000.gd.cn）
    var detectIntervalSec = 20; private set
    var heartbeatRetry = 3; private set
    var shieldSec = 30; private set

    // ---- 主题 ----
    var themeMode = "system"; private set       // system | light | dark
    var themePalette = "brand"; private set     // dynamic | brand | teal | violet | orange | pink
    var themeAmoled = false; private set        // 深色模式下背景纯黑
    /** 启动图标背景：true=跟随壁纸莫奈取色（Android 12+，默认），false=品牌灰 */
    var iconMonet = true; private set

    /** 上次用户是否让服务在运行（跟随服务生命周期，而非引擎认证状态；仅开启 rememberRunning 时持久化） */
    var lastRunning = false; private set

    fun init(ctx: Context) {
        if (::sp.isInitialized) return
        sp = ctx.applicationContext.getSharedPreferences("be_prefs", Context.MODE_PRIVATE)
        loadAccounts()
        loadRules()
        activeIndex = sp.getInt("active_index", 0).coerceIn(0, accounts.size)
        ruleMode = RuleMode.from(sp.getString("rule_mode", "ALL")!!)
        autoBoot = sp.getBoolean("auto_boot", true)
        rememberRunning = sp.getBoolean("remember_running", true)
        ssidFallbackAuth = sp.getBoolean("ssid_fallback_auth", true)
        autoHarden = sp.getBoolean("auto_harden", true)
        hideInBackground = sp.getBoolean("hide_in_background", false)
        predictiveBack = sp.getBoolean("predictive_back", false)
        probeUrlsJson = sp.getString("probe_urls_json", "")!!
        domainMapJson = sp.getString("domain_map_json", "")!!
        detectIntervalSec = sp.getInt("detect_interval_sec", 20)
        heartbeatRetry = sp.getInt("heartbeat_retry", 3)
        shieldSec = sp.getInt("shield_sec", 30)
        lastRunning = sp.getBoolean("last_running", false)
        workMode = sp.getString("work_mode", "standard") ?: "standard"
        themeMode = sp.getString("theme_mode", "system") ?: "system"
        themePalette = sp.getString("theme_palette", "brand") ?: "brand"
        themeAmoled = sp.getBoolean("theme_amoled", false)
        iconMonet = sp.getBoolean("icon_monet", true)
    }

    private fun loadAccounts() {
        accounts.clear()
        val arr = JSONArray(sp.getString("accounts", "[]")!!)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            accounts.add(
                Account(
                    username = o.getString("username"),
                    password = Crypto.decrypt(o.optString("password")),
                    userAgent = UserAgents.migrateLegacy(
                        o.optString("user_agent"), o.optString("channel", "phone").ifEmpty { "phone" }
                    ),
                    note = o.optString("note"),
                )
            )
        }
    }

    private fun saveAccounts() {
        val arr = JSONArray()
        for (a in accounts) {
            // channel 字段保留派生值，旧版 APK 读到新数据不致错乱
            val legacyChannel = if (a.userAgent == UserAgents.PC) "pc" else "phone"
            arr.put(JSONObject().put("username", a.username)
                .put("password", Crypto.encrypt(a.password))
                .put("user_agent", a.userAgent)
                .put("channel", legacyChannel)
                .put("note", a.note))
        }
        sp.edit().putString("accounts", arr.toString()).apply()
    }

    fun accountsSnapshot(): List<Account> = accounts.toList()
    fun accountAt(i: Int): Account? = accounts.getOrNull(i)
    fun activeAccount(): Account? = accounts.getOrNull(activeIndex.coerceAtMost(accounts.size - 1))

    fun addAccount(a: Account) { accounts.add(a); saveAccounts() }
    fun updateAccount(i: Int, a: Account) { accounts.getOrNull(i)?.let { accounts[i] = a; saveAccounts() } }
    fun removeAccount(i: Int) {
        if (i in accounts.indices) {
            accounts.removeAt(i)
            if (activeIndex >= accounts.size) activeIndex = (accounts.size - 1).coerceAtLeast(0)
            saveAccounts(); persistActiveIndex()
        }
    }

    fun setActive(i: Int) { if (i in accounts.indices) { activeIndex = i; persistActiveIndex() } }
    private fun persistActiveIndex() = sp.edit().putInt("active_index", activeIndex).apply()

    // ---- 规则 ----
    private fun loadRules() {
        rules.clear()
        val arr = JSONArray(sp.getString("rules", "[]")!!)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            rules.add(WifiRule(o.getString("type"), o.getString("value"), o.optString("note"), o.optBoolean("enabled", true)))
        }
    }

    private fun saveRules() {
        val arr = JSONArray()
        for (r in rules) {
            arr.put(JSONObject().put("type", r.type).put("value", r.value).put("note", r.note).put("enabled", r.enabled))
        }
        sp.edit().putString("rules", arr.toString()).apply()
    }

    fun rulesSnapshot(): List<WifiRule> = rules.toList()
    fun addRule(r: WifiRule) { rules.add(r); saveRules() }
    fun updateRule(i: Int, r: WifiRule) { rules.getOrNull(i)?.let { rules[i] = r; saveRules() } }
    fun removeRule(i: Int) { if (i in rules.indices) { rules.removeAt(i); saveRules() } }
    fun setRuleMode(m: RuleMode) { ruleMode = m; sp.edit().putString("rule_mode", m.name).apply() }

    /**
     * 判定当前 WiFi 是否应触发认证。
     * @param ssid 当前 SSID（可能为 <unknown ssid> 或空）
     * @param bssid 当前 BSSID（可能为空）
     * @param ssidReadable 是否成功读取到了 SSID
     */
    fun shouldAuth(ssid: String?, bssid: String?, ssidReadable: Boolean): Boolean = when (ruleMode) {
        RuleMode.ALL -> true
        RuleMode.WHITELIST -> matchAny(ssid, bssid, ssidReadable)
        RuleMode.BLACKLIST -> !matchAny(ssid, bssid, ssidReadable)
    }

    private fun matchAny(ssid: String?, bssid: String?, readable: Boolean): Boolean {
        val knownSsid = !ssid.isNullOrEmpty() && ssid != "<unknown ssid>" && ssid != "0x"
        for (r in rules) {
            if (!r.enabled) continue
            val hit = when (r.type.lowercase()) {
                "bssid" -> !bssid.isNullOrEmpty() && bssid.equals(r.value, true)
                else -> knownSsidMatch(ssid, r.value, knownSsid)
            }
            if (hit) return true
        }
        // FR-2.4：白名单下读不到 SSID 且无 BSSID 命中时，由降级策略决定
        if (ruleMode == RuleMode.WHITELIST && !readable) return ssidFallbackAuth
        return false
    }

    private fun knownSsidMatch(ssid: String?, pattern: String, known: Boolean): Boolean {
        if (!known || ssid == null) return false
        val s = ssid.removeSurrounding("\"")
        return if (pattern.contains('*') || pattern.contains('?')) wildcardMatch(pattern, s) else s == pattern
    }

    /** 简易通配符匹配（* 任意串，? 单字符），大小写敏感 */
    fun wildcardMatch(pattern: String, text: String): Boolean {
        val p = pattern.toCharArray(); val t = text.toCharArray()
        var i = 0; var j = 0; var star = -1; var mark = 0
        while (j < t.size) {
            when {
                i < p.size && (p[i] == '?' || p[i] == t[j]) -> { i++; j++ }
                i < p.size && p[i] == '*' -> { star = i++; mark = j }
                star >= 0 -> { i = star + 1; j = ++mark }
                else -> return false
            }
        }
        while (i < p.size && p[i] == '*') i++
        return i == p.size
    }

    // ---- 设置读写 ----
    fun setWorkMode(v: String) {
        require(v in listOf("standard", "shizuku", "root")) { "bad workMode" }
        workMode = v; sp.edit().putString("work_mode", v).apply()
    }

    fun setAutoBoot(v: Boolean) { autoBoot = v; sp.edit().putBoolean("auto_boot", v).apply() }

    /** 关闭即清除运行记忆：此后无论之前是否启动过，重开/重启均为停止状态 */
    fun setRememberRunning(v: Boolean) {
        rememberRunning = v
        sp.edit().putBoolean("remember_running", v).apply()
        if (!v) setLastRunning(false)
    }

    /** 服务生命周期同步运行记忆（仅开启「记住运行状态」时写入；未连 WiFi 启动同样记为运行中） */
    fun persistServiceRunning(v: Boolean) { if (rememberRunning) setLastRunning(v) }
    fun setSsidFallbackAuth(v: Boolean) { ssidFallbackAuth = v; sp.edit().putBoolean("ssid_fallback_auth", v).apply() }
    fun setAutoHarden(v: Boolean) { autoHarden = v; sp.edit().putBoolean("auto_harden", v).apply() }
    fun setHideInBackground(v: Boolean) { hideInBackground = v; sp.edit().putBoolean("hide_in_background", v).apply() }
    fun setPredictiveBack(v: Boolean) { predictiveBack = v; sp.edit().putBoolean("predictive_back", v).apply() }
    fun setProbeUrlsJson(v: String) { probeUrlsJson = v; sp.edit().putString("probe_urls_json", v).apply() }
    fun setDomainMapJson(v: String) { domainMapJson = v; sp.edit().putString("domain_map_json", v).apply() }
    fun setDetectIntervalSec(v: Int) { detectIntervalSec = v; sp.edit().putInt("detect_interval_sec", v).apply() }
    fun setHeartbeatRetry(v: Int) { heartbeatRetry = v; sp.edit().putInt("heartbeat_retry", v).apply() }
    fun setShieldSec(v: Int) { shieldSec = v; sp.edit().putInt("shield_sec", v).apply() }
    fun setThemeMode(v: String) {
        require(v in listOf("system", "light", "dark")) { "bad themeMode" }
        themeMode = v; sp.edit().putString("theme_mode", v).apply()
    }
    fun setThemePalette(v: String) { themePalette = v; sp.edit().putString("theme_palette", v).apply() }
    fun setThemeAmoled(v: Boolean) { themeAmoled = v; sp.edit().putBoolean("theme_amoled", v).apply() }
    fun setIconMonet(v: Boolean) { iconMonet = v; sp.edit().putBoolean("icon_monet", v).apply() }
    fun setLastRunning(v: Boolean) { if (lastRunning != v) { lastRunning = v; sp.edit().putBoolean("last_running", v).apply() } }
}
