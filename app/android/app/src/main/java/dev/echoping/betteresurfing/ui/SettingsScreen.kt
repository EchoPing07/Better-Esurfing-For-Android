package dev.echoping.betteresurfing.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Rule
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Copyright
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.SettingsSuggest
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import dev.echoping.betteresurfing.BeApplication
import dev.echoping.betteresurfing.LauncherIcon
import androidx.compose.ui.res.painterResource
import dev.echoping.betteresurfing.BuildConfig
import dev.echoping.betteresurfing.R
import androidx.compose.ui.unit.dp
import dev.echoping.betteresurfing.keep.KeepAliveWorker
import dev.echoping.betteresurfing.privilege.Mode
import dev.echoping.betteresurfing.privilege.Privilege
import dev.echoping.betteresurfing.store.Prefs
import dev.echoping.betteresurfing.ui.theme.BuiltInPalettes
import dev.echoping.betteresurfing.ui.theme.DYNAMIC_PALETTE_ID
import dev.echoping.betteresurfing.ui.theme.ExtTheme
import dev.echoping.betteresurfing.ui.theme.ThemeController
import dev.echoping.betteresurfing.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置一级页。二级页已拍平进全局 NavDisplay 返回栈（见 ui/Nav.kt），
 * 不再自管子栈：子页转场 / 预测性返回与顶层路由完全同一套系统默认行为。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpen: (BeNavKey) -> Unit) {
    SettingsRoot(onBack) { SettingsMain(onOpen) }
}

/** 二级页包装：SubPage 骨架（TopAppBar 返回） + 子页内容，供全局 NavDisplay 直接挂载。 */
@Composable
fun ModeSettingsScreen(onBack: () -> Unit) = SubPage("工作模式", onBack) { ModeSubPage {} }

@Composable
fun RulesSettingsScreen(onBack: () -> Unit) = SubPage("认证规则", onBack) { RulesScreen {} }

@Composable
fun AdvancedSettingsScreen(onBack: () -> Unit) = SubPage("高级设置", onBack) { AdvancedSubPage {} }

@Composable
fun KeepAliveSettingsScreen(onBack: () -> Unit) = SubPage("自启动与保活", onBack) { KeepAliveSubPage {} }

@Composable
fun ThemeSettingsScreen(onBack: () -> Unit) = SubPage("主题", onBack) { ThemeSubPage {} }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsRoot(onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        snackbarHost = { SnackbarHost(LocalSnackbar.current) },
    ) { pad ->
        Box(Modifier.scaffoldEdgeToEdgePadding(pad)) { content() }
    }
}

@Composable
private fun SettingsMain(open: (BeNavKey) -> Unit) {
    val ctx = LocalContext.current
    val st = remember { Privilege.detect(ctx) }
    val mode = Mode.fromId(Prefs.workMode)
    val ruleCount = Prefs.rulesSnapshot().size
    val modeText = when (mode) {
        Mode.STANDARD -> "标准模式"
        Mode.SHIZUKU -> "Shizuku" + (if (!st.shizukuInstalled) " · 未安装" else "")
        Mode.ROOT -> "Root" + (if (!st.rootAvailable) " · 无 su" else "")
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
            .padding(bottom = navigationBarsBottom()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        EntryGroup(
            "引擎",
            { Entry(Icons.Outlined.Security, "工作模式", modeText) { open(BeNavKey.SettingsMode) } },
            { Entry(Icons.AutoMirrored.Outlined.Rule, "认证规则", "${ruleModeText()} · $ruleCount 条规则") { open(BeNavKey.SettingsRules) } },
        )
        EntryGroup(
            "外观",
            { Entry(Icons.Outlined.Palette, "主题", themeSummary()) { open(BeNavKey.SettingsTheme) } },
        )
        EntryGroup(
            "网络",
            { Entry(Icons.Outlined.SettingsSuggest, "高级设置", "探针 URL · 域名映射 · 间隔") { open(BeNavKey.SettingsAdvanced) } },
        )
        EntryGroup(
            "系统",
            { Entry(Icons.Outlined.BatterySaver, "自启动与保活", if (Prefs.autoBoot) "开机自启：开" else "开机自启：关") { open(BeNavKey.SettingsKeepAlive) } },
            {
                SwitchRow(
                    "预测性返回手势", UiToggles.predictiveBack,
                    "侧滑返回时跟随手势预览上一页（Android 14+）",
                ) {
                    UiToggles.setPredictiveBack(it)
                    // SukiSU Ultra 同款：立即反射系统级总闸 + recreate 重建窗口生效
                    // （Application.onCreate 只在进程启动时跑一次，进程内切换靠这里）。
                    // API 33 上 setter 不存在，runCatching 静默，开关仅对 34+ 生效。
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        BeApplication.setEnableOnBackInvokedCallback(ctx.applicationInfo, it)
                        (ctx as? Activity)?.recreate()
                    }
                }
            },
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun EntryGroup(title: String, vararg rows: @Composable () -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, bottom = 6.dp),
    )
    // 首页式分组：每行独立卡片，行间 2dp 细缝
    GroupedColumn(rows.toList())
}

private fun ruleModeText(): String = when (Prefs.ruleMode) {
    dev.echoping.betteresurfing.store.RuleMode.WHITELIST -> "白名单"
    dev.echoping.betteresurfing.store.RuleMode.BLACKLIST -> "黑名单"
    else -> "全部认证"
}

private fun themeSummary(): String {
    val modeLabel = ThemeController.mode.label
    val paletteLabel = if (ThemeController.paletteId == DYNAMIC_PALETTE_ID) "动态取色" else
        BuiltInPalettes.firstOrNull { it.id == ThemeController.paletteId }?.label ?: "默认蓝"
    return "$paletteLabel · $modeLabel"
}

@Composable
private fun Entry(icon: ImageVector, title: String, summary: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        leadingContent = {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        trailingContent = {
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                null,
                tint = MaterialTheme.colorScheme.outline,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubPage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        snackbarHost = { SnackbarHost(LocalSnackbar.current) },
    ) { pad ->
        Box(Modifier.scaffoldEdgeToEdgePadding(pad).fillMaxSize().padding(start = 16.dp, top = 16.dp, end = 16.dp)) { content() }
    }
}

@Composable
private fun ModeSubPage(onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val epoch by dev.echoping.betteresurfing.engine.Repo.shizukuEpoch.collectAsState()
    var manualRefresh by remember { mutableIntStateOf(0) }
    val st = remember(epoch, manualRefresh) { Privilege.detect(ctx, force = true) }
    var sel by remember { mutableStateOf(Mode.fromId(Prefs.workMode)) }
    var suTest by remember { mutableStateOf<String?>(null) }
    var shizukuTest by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(bottom = navigationBarsBottom(16.dp)),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        listOf(Mode.STANDARD, Mode.SHIZUKU, Mode.ROOT).forEach { m ->
            val ready = Privilege.modeReady(m, st)
            val selected = sel == m
            Card(
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(
                    containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerLowest,
                ),
                onClick = {
                    sel = m
                    Prefs.setWorkMode(m.id)
                    Privilege.invalidate()
                    manualRefresh++
                    onChanged()
                    dev.echoping.betteresurfing.engine.Repo.onLog(1, "工作模式切换为 ${m.label}")
                },
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selected, onClick = null)
                        Text(m.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Surface(
                            color = if (ready) ExtTheme.colors.successContainer
                            else MaterialTheme.colorScheme.errorContainer,
                            contentColor = if (ready) ExtTheme.colors.onSuccessContainer
                            else MaterialTheme.colorScheme.onErrorContainer,
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Row(
                                Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    if (ready) Icons.Outlined.CheckCircle else Icons.Outlined.Cancel,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    if (ready) "可用" else "不可用",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                        }
                    }
                    Text(
                        when (m) {
                            Mode.STANDARD -> "无需额外权限，读 WiFi 名需定位权限"
                            Mode.SHIZUKU -> when {
                                st.shizukuReady -> "已就绪，免定位读 WiFi 名"
                                st.shizukuRunning -> "运行中但未授权，请点击请求授权"
                                st.shizukuInstalled -> "服务未运行，请在 Shizuku 应用启动"
                                else -> "未安装 Shizuku 应用"
                            }
                            Mode.ROOT ->
                                if (st.rootAvailable) "su 可用，免定位读 WiFi 名"
                                else "未检测到 su，请确认已 root 并授权"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (sel == Mode.ROOT && st.rootAvailable) {
            OutlinedButton(onClick = {
                scope.launch {
                    // su 可能弹授权框挂起，放后台线程避免卡 UI
                    val (ok, out) = withContext(Dispatchers.IO) { Privilege.runAsRoot("id") }
                    suTest = if (ok) out else "执行失败：$out"
                    dev.echoping.betteresurfing.engine.Repo.onLog(1, "Root 自检: $suTest")
                }
            }) { Text("测试 su 权限") }
            suTest?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }

        if (sel == Mode.SHIZUKU) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (st.shizukuRunning && !st.shizukuGranted) {
                    Button(onClick = { Privilege.requestShizukuPermission() }) { Text("请求授权") }
                }
                if (st.shizukuReady) {
                    // runAsShizuku 首次会同步 bind UserService（binder 回调投递在主线程），
                    // 必须在后台线程执行，否则锁死主线程必现 ANR
                    Button(onClick = {
                        scope.launch {
                            shizukuTest = withContext(Dispatchers.IO) { shizukuSelfTest() }
                        }
                    }) { Text("自检执行 id") }
                }
                if (st.shizukuInstalled && !st.shizukuRunning) {
                    OutlinedButton(onClick = {
                        try {
                            ctx.startActivity(ctx.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api"))
                        } catch (e: Exception) {
                            dev.echoping.betteresurfing.engine.Repo.onLog(3, "打开 Shizuku 失败: ${e.message}")
                        }
                    }) { Text("打开 Shizuku") }
                }
            }
            shizukuTest?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LaunchedEffect(st.shizukuReady) {
                // 同上：自检走 UserService 绑定，放后台线程
                if (st.shizukuReady) shizukuTest = withContext(Dispatchers.IO) { shizukuSelfTest() }
            }
        }

        TextButton(onClick = { Privilege.invalidate(); manualRefresh++ }) {
            Text("重新检测能力")
        }
    }
}

private fun shizukuSelfTest(): String {
    val (ok, out) = Privilege.runAsShizuku("id")
    val msg = if (ok) "Shizuku 自检通过：$out" else "Shizuku 自检失败：$out"
    dev.echoping.betteresurfing.engine.Repo.onLog(if (ok) 1 else 3, msg)
    return msg
}

@Composable
private fun AdvancedSubPage(onChanged: () -> Unit) {
    val snack = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    var detectInt by remember { mutableIntStateOf(Prefs.detectIntervalSec) }
    var beatRetry by remember { mutableIntStateOf(Prefs.heartbeatRetry) }
    var shield by remember { mutableIntStateOf(Prefs.shieldSec) }
    var probes by remember { mutableStateOf(Prefs.probeUrlsJson) }
    var domainMap by remember { mutableStateOf(Prefs.domainMapJson) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(bottom = navigationBarsBottom(16.dp)),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "默认值适用于广东电信天翼校园网，一般无需修改。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        // 网络探测
        SettingsHeader("网络探测")
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = probes, onValueChange = { probes = it },
                    label = { Text("探针 URL") },
                    supportingText = { Text("JSON 数组，留空使用默认") },
                    placeholder = { Text("""["http://connect.rom.miui.com/generate_204"]""") },
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = domainMap, onValueChange = { domainMap = it },
                    label = { Text("域名 IP 映射") },
                    supportingText = { Text("JSON 对象，留空使用内置") },
                    placeholder = { Text("""{"enet.10000.gd.cn":"125.88.59.131"}""") },
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // 计时与重试
        SettingsHeader("计时与重试", Modifier.padding(top = 4.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField("空闲巡检间隔 · 秒", detectInt) { v -> detectInt = v }
                NumberField("心跳失败重试次数", beatRetry) { v -> beatRetry = v }
                NumberField("认证护盾窗口 · 秒", shield) { v -> shield = v }
            }
        }

        Button(
            onClick = {
                runCatching {
                    if (probes.isNotBlank()) org.json.JSONArray(probes)
                    if (domainMap.isNotBlank()) org.json.JSONObject(domainMap)
                }.onSuccess {
                    Prefs.setProbeUrlsJson(probes.trim())
                    Prefs.setDomainMapJson(domainMap.trim())
                    Prefs.setDetectIntervalSec(detectInt)
                    Prefs.setHeartbeatRetry(beatRetry)
                    Prefs.setShieldSec(shield)
                    onChanged()
                    scope.launch { snack.showSnackbar("已保存，重启认证后生效") }
                }.onFailure {
                    scope.launch { snack.showSnackbar("JSON 格式有误，未保存") }
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = MaterialTheme.shapes.large,
        ) { Text("保存") }
    }
}

@Composable
private fun KeepAliveSubPage(onChanged: () -> Unit) {
    val ctx = LocalContext.current
    var autoBoot by remember { mutableStateOf(Prefs.autoBoot) }
    var rememberRun by remember { mutableStateOf(Prefs.rememberRunning) }
    var fallback by remember { mutableStateOf(Prefs.ssidFallbackAuth) }
    var autoHarden by remember { mutableStateOf(Prefs.autoHarden) }
    var hideBg by remember { mutableStateOf(Prefs.hideInBackground) }
    val st = remember { Privilege.detect(ctx) }
    val mode = Mode.fromId(Prefs.workMode)
    val privileged = (mode == Mode.ROOT && st.rootAvailable) || (mode == Mode.SHIZUKU && st.shizukuReady)
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(bottom = navigationBarsBottom(16.dp)),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GroupedColumn(
            listOf(
                {
                    SwitchRow("开机自动启动", autoBoot, "开机后自动启动并恢复运行状态") { v ->
                        autoBoot = v
                        Prefs.setAutoBoot(v)
                        if (v) KeepAliveWorker.schedule(ctx)
                        onChanged()
                    }
                },
                {
                    SwitchRow(
                        "记住运行状态", rememberRun,
                        "杀后台或重启后自动恢复运行，关闭则重开一律为停止状态",
                    ) { v ->
                        rememberRun = v
                        Prefs.setRememberRunning(v)
                        onChanged()
                    }
                },
                {
                    SwitchRow(
                        "特权模式自动加固保活", autoHarden,
                        if (privileged) "启动时写入 doze 白名单与后台权限"
                        else "需 Shizuku 或 Root 模式",
                    ) { v ->
                        autoHarden = v
                        Prefs.setAutoHarden(v)
                        onChanged()
                    }
                },
                {
                    SwitchRow("读不到 WiFi 名时仍尝试认证", fallback, "关闭后白名单下读不到 WiFi 名则不认证") { v ->
                        fallback = v
                        Prefs.setSsidFallbackAuth(v)
                        onChanged()
                    }
                },
            )
        )

        SettingsHeader("隐藏后台", Modifier.padding(top = 4.dp))
        GroupedColumn(
            listOf {
                SwitchRow(
                    "不在最近任务中显示", hideBg,
                    "离开应用后从最近任务移除，服务照常后台运行",
                ) { v ->
                    hideBg = v
                    Prefs.setHideInBackground(v)
                    onChanged()
                }
            },
        )

        if (privileged) {
            Button(
                onClick = {
                    scope.launch {
                        val results = withContext(Dispatchers.IO) { Privilege.hardenKeepAlive(ctx) }
                        results.forEach { (cmd, ok, out) ->
                            dev.echoping.betteresurfing.engine.Repo.onLog(
                                if (ok) 1 else 2, "保活加固 $cmd → " + if (ok) "成功" else "失败 ${out.take(60)}",
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = MaterialTheme.shapes.large,
            ) { Text("立即加固保活") }
        }

        Button(
            onClick = { requestIgnoreBatteryOptimization(ctx) },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = MaterialTheme.shapes.large,
        ) { Text("申请电池优化豁免") }
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("仍被杀后台？按机型检查", style = MaterialTheme.typography.titleSmall)
                Text("· 小米 / Redmi：省电策略选无限制，最近任务下拉锁定卡片", style = MaterialTheme.typography.bodySmall)
                Text("· OPPO / vivo / 一加：允许完全后台行为并开启自启动", style = MaterialTheme.typography.bodySmall)
                Text("· 荣耀 / 华为：启动管理改手动并全开三项", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * 关于页：已从设置迁到首页入口（日志下方），保持独立路由。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    SubPage("关于", onBack) { AboutSubPage() }
}

private const val REPO_URL = "https://github.com/EchoPing07/Better-Esurfing-For-Android"
private const val LICENSE_URL = "$REPO_URL/blob/main/LICENSE"
private const val RELEASES_URL = "$REPO_URL/releases"

@Composable
private fun AboutSubPage() {
    val ctx = LocalContext.current

    fun openUrl(url: String) {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { dev.echoping.betteresurfing.engine.Repo.onLog(3, "打开链接失败: ${it.message}") }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(bottom = navigationBarsBottom(16.dp)),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 头部：应用图标 / 名称 / 版本，直接铺在页面背景上
        Column(
            Modifier.fillMaxWidth().padding(vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_about_logo),
                contentDescription = "应用图标",
                modifier = Modifier.size(72.dp),
            )
            Text("BetterES", style = MaterialTheme.typography.headlineSmall)
            Text(
                "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 关于分组：源码 / 协议 / 更新
        SettingsHeader("关于")
        GroupedColumn(
            listOf(
                { Entry(Icons.Outlined.Code, "查看源代码", "在 GitHub 上查看源代码") { openUrl(REPO_URL) } },
                { Entry(Icons.Outlined.Copyright, "开源协议", "MIT License") { openUrl(LICENSE_URL) } },
                { Entry(Icons.Outlined.SystemUpdateAlt, "检查更新", "检查软件更新和新功能") { openUrl(RELEASES_URL) } },
            )
        )
    }
}

/** 分组小节标题（与首页 EntryGroup 标题同款样式） */
@Composable
private fun SettingsHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 16.dp),
    )
}

@Composable
private fun ThemeSubPage(onChanged: () -> Unit) {
    val dynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val ctx = LocalContext.current

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(bottom = navigationBarsBottom(16.dp)),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 通用设置
        SettingsHeader("通用设置")
        GroupedColumn(
            buildList {
                add {
                    DropdownSettingRow(
                        label = "颜色模式",
                        desc = when (ThemeController.mode) {
                            ThemeMode.SYSTEM -> "亮暗跟随系统设置"
                            ThemeMode.LIGHT -> "始终使用浅色配色"
                            ThemeMode.DARK -> "始终使用深色配色"
                        },
                        options = ThemeMode.entries.map { it to it.label },
                        selected = ThemeController.mode,
                        onSelect = { m ->
                            ThemeController.setMode(m)
                            onChanged()
                        },
                    )
                }
                add {
                    SwitchRow(
                        label = "AMOLED 纯黑",
                        checked = ThemeController.amoled,
                        desc = "深色模式下背景为纯黑",
                    ) { v ->
                        ThemeController.setAmoled(v)
                        onChanged()
                    }
                }
                // 桌面图标换色（activity-alias 切换，Android 12+ 才有莫奈取色）
                if (dynamicAvailable) {
                    add {
                        SwitchRow(
                            label = "启动图标跟随壁纸取色",
                            checked = Prefs.iconMonet,
                            desc = "关闭则固定品牌灰背景；切换后个别启动器需几秒刷新",
                        ) { v ->
                            Prefs.setIconMonet(v)
                            LauncherIcon.apply(ctx)
                            onChanged()
                        }
                    }
                }
            }
        )

        // 配色方案
        SettingsHeader("配色方案", Modifier.padding(top = 4.dp))
        GroupedColumn(
            buildList {
                add {
                    PaletteRow(
                        label = "动态取色",
                        desc = if (dynamicAvailable) "跟随壁纸 · Material You" else "需要 Android 12+",
                        preview = dynamicPreviewColors(),
                        selected = ThemeController.paletteId == DYNAMIC_PALETTE_ID,
                        enabled = dynamicAvailable,
                        onClick = {
                            ThemeController.setPalette(DYNAMIC_PALETTE_ID)
                            onChanged()
                        },
                    )
                }
                BuiltInPalettes.forEach { p ->
                    add {
                        PaletteRow(
                            label = p.label,
                            preview = p.previewColors,
                            selected = ThemeController.paletteId == p.id,
                            onClick = {
                                ThemeController.setPalette(p.id)
                                onChanged()
                            },
                        )
                    }
                }
            }
        )

        Text(
            "在线与认证中的状态色固定，不随配色变化。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
        )
    }
}

@Composable
private fun dynamicPreviewColors(): Triple<Color, Color, Color>? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val ctx = LocalContext.current
    return remember {
        val scheme = androidx.compose.material3.dynamicLightColorScheme(ctx)
        Triple(scheme.primary, scheme.tertiary, scheme.surfaceVariant)
    }
}

@Composable
private fun PaletteRow(
    label: String,
    preview: Triple<Color, Color, Color>?,
    selected: Boolean,
    enabled: Boolean = true,
    desc: String = "",
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = { if (desc.isNotBlank()) Text(desc) },
        leadingContent = { PaletteDisc(preview, selected) },
        trailingContent = { RadioButton(selected = selected, onClick = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    )
}

/**
 * Material You 式配色圆盘：左半为主色，右上 / 右下两格为两种辅色（与壁纸上色选择器同款）。
 * preview 三色序：<主色 Primary, 辅色 Tertiary, 辅色 SurfaceVariant>
 */
@Composable
private fun PaletteDisc(preview: Triple<Color, Color, Color>?, selected: Boolean) {
    val fallback = MaterialTheme.colorScheme.surfaceContainerHighest
    val (main, aux1, aux2) = preview ?: Triple(fallback, fallback, fallback)
    val ring =
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.size(36.dp).clip(CircleShape)) {
        // drawArc 角度：0° → 3 点钟方向，顺时针递增
        drawArc(color = main, startAngle = 90f, sweepAngle = 180f, useCenter = true)   // 左半：主色
        drawArc(color = aux1, startAngle = 270f, sweepAngle = 90f, useCenter = true)   // 右上
        drawArc(color = aux2, startAngle = 0f, sweepAngle = 90f, useCenter = true)     // 右下
        drawCircle(
            color = ring,
            radius = (size.minDimension / 2f) - (if (selected) 2.dp else 1.dp).toPx() / 2,
            style = Stroke(width = (if (selected) 2.dp else 1.dp).toPx()),
        )
    }
}

@Composable
private fun NumberField(label: String, value: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { s ->
            text = s.filter { it.isDigit() }
            text.toIntOrNull()?.let(onChange)
        },
        label = { Text(label) },
        singleLine = true,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, desc: String = "", onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = { if (desc.isNotBlank()) Text(desc) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

private fun requestIgnoreBatteryOptimization(ctx: Context) {
    try {
        ctx.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${ctx.packageName}")),
        )
    } catch (e: Exception) {
        try {
            ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (_: Exception) {
        }
    }
}
