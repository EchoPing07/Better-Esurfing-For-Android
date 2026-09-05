package dev.echoping.betteresurfing

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import dev.echoping.betteresurfing.keep.KeepAliveWorker
import dev.echoping.betteresurfing.service.AuthService
import dev.echoping.betteresurfing.store.Prefs
import dev.echoping.betteresurfing.ui.AboutScreen
import dev.echoping.betteresurfing.ui.AdvancedSettingsScreen
import dev.echoping.betteresurfing.ui.BeNavKey
import dev.echoping.betteresurfing.ui.BeNavSavedStateConfig
import dev.echoping.betteresurfing.ui.bePopTransitionSpec
import dev.echoping.betteresurfing.ui.bePredictivePopTransitionSpec
import dev.echoping.betteresurfing.ui.beTransitionSpec
import dev.echoping.betteresurfing.ui.DashboardScreen
import dev.echoping.betteresurfing.ui.KeepAliveSettingsScreen
import dev.echoping.betteresurfing.ui.LocalSnackbar
import dev.echoping.betteresurfing.ui.LogsScreen
import dev.echoping.betteresurfing.ui.ModeSettingsScreen
import dev.echoping.betteresurfing.ui.RulesSettingsScreen
import dev.echoping.betteresurfing.ui.SettingsScreen
import dev.echoping.betteresurfing.ui.ThemeSettingsScreen
import dev.echoping.betteresurfing.ui.scaffoldEdgeToEdgePadding
import dev.echoping.betteresurfing.ui.theme.BeMotion
import dev.echoping.betteresurfing.ui.theme.BetterEsurfingTheme
import dev.echoping.betteresurfing.ui.theme.LocalReduceMotion
import dev.echoping.betteresurfing.ui.UiToggles
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // enableEdgeToEdge 默认的 auto 导航栏样式会开启系统对比度遮罩
        // （isNavigationBarContrastEnforced=true）：Android 15 强制 edge-to-edge 后，
        // 系统在小白条区域铺一层近白色 scrim（实测约 92% 不透明白），表现为底部
        // 一条白色导航栏背景。关掉它，小白条才真正透明悬浮在内容上。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        Prefs.init(this)
        KeepAliveWorker.schedule(this)
        requestNeededPermissions()

        setContent {
            BetterEsurfingTheme {
                App()
            }
        }

        if (Prefs.lastRunning && Prefs.activeAccount() != null && !AuthService.isRunning) {
            AuthService.start(this)
        }
    }

    override fun onResume() {
        super.onResume()
        dev.echoping.betteresurfing.privilege.Privilege.invalidate()
        // 定位开关/授权/工作模式可能在后台变化：回前台时刷新一次 WiFi 事实
        dev.echoping.betteresurfing.net.NetWatch.refresh()
    }

    override fun onStop() {
        super.onStop()
        // 隐藏后台：不再可见即从最近任务移除（前台服务不受影响）；
        // 旋转等配置变化不算离开，跳过
        if (Prefs.hideInBackground && !isChangingConfigurations) {
            finishAndRemoveTask()
        }
    }

    private fun requestNeededPermissions() {
        val wanted = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= 33) {
            wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val need = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (need.isNotEmpty()) permLauncher.launch(need.toTypedArray())
    }
}

/**
 * 导航：Navigation3 NavDisplay + SukiSU Ultra 同款滑动转场（ui/NavTransition.kt，
 * 参数逐行取自 SukiSU 实际依赖的 miuix-navigation3-ui 0.9.3 fork）：
 * 前进/返回 500ms 水平平移 + 1/4 视差（阻尼振荡 easing），预测性返回同几何
 * LinearEasing 手势驱动；rememberNavBackStack 进程死亡恢复。
 *
 * 「预测性返回手势」开关（SukiSU Ultra 同款系统级方案）：
 * BeApplication 启动时（API 34+）反射 setEnableOnBackInvokedCallback 按偏好
 * opt-in/out，设置页切换时立即反射 + recreate() 重建生效。
 * 关：应用未 opt-in → 传统返回，松手后播返回滑动转场；
 * 开：手势进度直达 NavDisplay → 跟手滑动预览，反向滑可取消。
 */
@Composable
private fun App() {
    // navigation3 1.0.0 的 rememberNavBackStack 未泛型化，栈元素类型为 NavKey
    val backStack = rememberNavBackStack(BeNavSavedStateConfig, BeNavKey.Home)
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    UiToggles.ensureLoaded()

    fun back() {
        backStack.removeLastOrNull()
    }

    fun open(key: BeNavKey) {
        if (backStack.last() != key) backStack.add(key)
    }

    CompositionLocalProvider(LocalSnackbar provides snackbar) {
        NavDisplay(
            backStack = backStack,
            // SukiSU Ultra（miuix fork）同款滑动转场，见 ui/NavTransition.kt
            transitionSpec = beTransitionSpec(),
            popTransitionSpec = bePopTransitionSpec(),
            predictivePopTransitionSpec = bePredictivePopTransitionSpec(),
            entryProvider = entryProvider {
                entry<BeNavKey.Home> {
                    HomeScreen(
                        ctx = ctx,
                        snackbar = snackbar,
                        onOpenSettings = { open(BeNavKey.Settings) },
                        onOpenLogs = { open(BeNavKey.Logs) },
                        onOpenAbout = { open(BeNavKey.About) },
                    )
                }
                entry<BeNavKey.Settings> {
                    SettingsScreen(onBack = ::back, onOpen = ::open)
                }
                entry<BeNavKey.SettingsMode> { ModeSettingsScreen(onBack = ::back) }
                entry<BeNavKey.SettingsRules> { RulesSettingsScreen(onBack = ::back) }
                entry<BeNavKey.SettingsAdvanced> { AdvancedSettingsScreen(onBack = ::back) }
                entry<BeNavKey.SettingsKeepAlive> { KeepAliveSettingsScreen(onBack = ::back) }
                entry<BeNavKey.SettingsTheme> { ThemeSettingsScreen(onBack = ::back) }
                entry<BeNavKey.Logs> { LogsScreen(onBack = ::back) }
                entry<BeNavKey.About> { AboutScreen(onBack = ::back) }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    ctx: android.content.Context,
    snackbar: SnackbarHostState,
    onOpenSettings: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val scroll = rememberScrollState()
    var accountSheet by remember { mutableStateOf(false) }
    val reduce = LocalReduceMotion.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val fabExpanded by remember { derivedStateOf { scroll.value < 88 } }
    // 大标题顶部起于内容区 56dp；滚动 48dp 后换为顶栏小标题（LSPosed 式折叠）
    val barTitle by remember { derivedStateOf { with(density) { scroll.value.toDp() > 48.dp } } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        topBar = {
            TopAppBar(
                title = {
                    AnimatedVisibility(
                        visible = barTitle,
                        enter = fadeIn(tween(BeMotion.dur(BeMotion.SHORT4, reduce))),
                        exit = fadeOut(tween(BeMotion.dur(BeMotion.SHORT4, reduce))),
                    ) { Text("BetterES") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            RunFab(
                ctx = ctx,
                expanded = fabExpanded,
                onNeedAccount = { accountSheet = true },
                onNoWifi = {
                    scope.launch { snackbar.showSnackbar("未连接无线网络，连接 WiFi 后将自动认证") }
                },
            )
        },
    ) { pad ->
        DashboardScreen(
            ctx = ctx,
            scrollState = scroll,
            showAccountSheet = accountSheet,
            onShowAccountSheetChange = { accountSheet = it },
            onOpenLogs = onOpenLogs,
            onOpenSettings = onOpenSettings,
            onOpenAbout = onOpenAbout,
            // 底部不避让导航栏：内容滚动滑入小白条下方（真正 edge-to-edge）
            modifier = Modifier.scaffoldEdgeToEdgePadding(pad),
        )
    }
}

@Composable
private fun RunFab(
    ctx: android.content.Context,
    expanded: Boolean,
    onNeedAccount: () -> Unit,
    onNoWifi: () -> Unit,
) {
    val running by AuthService.running.collectAsState()
    val reduce = LocalReduceMotion.current
    val dur = BeMotion.dur(BeMotion.SHORT4, reduce)
    val container by animateColorAsState(
        if (running) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.primary,
        BeMotion.emphasized(BeMotion.dur(BeMotion.MEDIUM2, reduce)),
        label = "fabBg",
    )
    val content by animateColorAsState(
        if (running) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.onPrimary,
        BeMotion.emphasized(BeMotion.dur(BeMotion.MEDIUM2, reduce)),
        label = "fabFg",
    )

    ExtendedFloatingActionButton(
        onClick = {
            if (running) {
                AuthService.stop(ctx)
            } else {
                if (Prefs.accountsSnapshot().isEmpty()) onNeedAccount()
                else {
                    // 未连 WiFi 仍可启动服务：引擎会在连接后自动开始
                    if (!dev.echoping.betteresurfing.net.NetWatch.isConnectedNow()) onNoWifi()
                    AuthService.start(ctx)
                }
            }
        },
        expanded = expanded,
        containerColor = container,
        contentColor = content,
        icon = {
            AnimatedContent(
                targetState = running,
                transitionSpec = {
                    (scaleIn(BeMotion.emphasized(dur), initialScale = 0.5f) + fadeIn(tween(dur))) togetherWith
                        (scaleOut(BeMotion.emphasized(dur), targetScale = 0.5f) + fadeOut(tween(dur)))
                },
                label = "fabIcon",
            ) { isRunning ->
                Icon(
                    if (isRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    contentDescription = null,
                )
            }
        },
        text = {
            AnimatedContent(
                targetState = running,
                transitionSpec = { fadeIn(tween(dur)) togetherWith fadeOut(tween(dur)) },
                label = "fabText",
            ) { isRunning -> Text(if (isRunning) "停止" else "启动") }
        },
    )
}
