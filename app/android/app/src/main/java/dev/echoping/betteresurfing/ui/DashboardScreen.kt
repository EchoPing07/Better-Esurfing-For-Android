package dev.echoping.betteresurfing.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.echoping.be.mobile.Mobile
import dev.echoping.betteresurfing.engine.LogLine
import dev.echoping.betteresurfing.engine.Repo
import dev.echoping.betteresurfing.engine.UiState
import dev.echoping.betteresurfing.net.NetWatch
import dev.echoping.betteresurfing.privilege.Mode
import dev.echoping.betteresurfing.privilege.Privilege
import dev.echoping.betteresurfing.service.AuthService
import dev.echoping.betteresurfing.store.Account
import dev.echoping.betteresurfing.store.Prefs
import dev.echoping.betteresurfing.store.RuleMode
import dev.echoping.betteresurfing.store.UserAgents
import dev.echoping.betteresurfing.ui.theme.BeMotion
import dev.echoping.betteresurfing.ui.theme.ExtTheme
import dev.echoping.betteresurfing.ui.theme.LocalReduceMotion
import kotlinx.coroutines.launch

/**
 * 首页：下沉大标题（滚动后由顶部小标题接管）→ 状态条 → 事实卡（工作模式/当前网络/IP）→
 * 账号卡 → 日志 → 关于。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    ctx: android.content.Context,
    scrollState: androidx.compose.foundation.ScrollState = rememberScrollState(),
    showAccountSheet: Boolean = false,
    onShowAccountSheetChange: (Boolean) -> Unit = {},
    onOpenLogs: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by Repo.state.collectAsState()
    val serviceRunning by AuthService.running.collectAsState()
    val epoch by Repo.shizukuEpoch.collectAsState()
    var accounts by remember { mutableStateOf(Prefs.accountsSnapshot()) }
    var activeIdx by remember { mutableIntStateOf(Prefs.activeIndex) }
    val view = LocalView.current

    fun refreshAccounts() {
        accounts = Prefs.accountsSnapshot()
        activeIdx = Prefs.activeIndex
    }

    LaunchedEffect(ctx) {
        // 进入首页时触发一次全量刷新（含特权读取，NetWatch 内部在工作线程执行）
        NetWatch.refresh()
    }
    LaunchedEffect(epoch) { activeIdx = Prefs.activeIndex }
    LaunchedEffect(state.engineState) {
        if (state.engineState == Mobile.StateOnline) hapticConfirm(view)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp)
            .padding(bottom = 96.dp + navigationBarsBottom()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HomeHeader()
        StatusStrip(state, serviceRunning)
        NetworkFactsCard(state, ctx)
        // 分组入口：账号 / 日志 / 设置 / 关于，首尾大圆角、行间细缝
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            CurrentAccountCard(
                accounts = accounts,
                activeIdx = activeIdx.coerceIn(0, (accounts.size - 1).coerceAtLeast(0)),
                onOpenSheet = { onShowAccountSheetChange(true) },
                shape = groupFirstShape(),
            )
            HomeEntryCard(
                Icons.AutoMirrored.Outlined.ReceiptLong, "日志", onOpenLogs,
                shape = groupMidShape(),
            )
            HomeEntryCard(Icons.Outlined.Settings, "设置", onOpenSettings, shape = groupMidShape())
            HomeEntryCard(Icons.Outlined.Info, "关于", onOpenAbout, shape = groupLastShape())
        }
    }

    if (showAccountSheet) {
        AccountManageSheet(
            ctx = ctx,
            accounts = accounts,
            activeIdx = activeIdx,
            onDismiss = { onShowAccountSheetChange(false) },
            onSelect = { i ->
                Prefs.setActive(i)
                refreshAccounts()
                if (AuthService.isRunning) AuthService.loginNow(ctx, i)
            },
            onChanged = ::refreshAccounts,
        )
    }
}

private fun hapticConfirm(view: View) {
    if (Build.VERSION.SDK_INT >= 30) {
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    } else {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }
}

/**
 * 下沉大标题：初始低于顶部，滚动离开视口后由 MainActivity 的顶栏小标题接管。
 */
@Composable
private fun HomeHeader() {
    Text(
        "BetterES",
        style = MaterialTheme.typography.displaySmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 56.dp, bottom = 12.dp),
    )
}

// ================= 状态条 =================

/**
 * 状态条：主词 + 固定占位副行 —— 高度恒定，不随状态变化。
 * 文案/图标全部来自 [AuthStatus.presentation]，与前台通知同源。
 */
@Composable
private fun StatusStrip(state: UiState, serviceRunning: Boolean) {
    val v = AuthStatus.presentation(state, serviceRunning)
    val reduce = LocalReduceMotion.current
    val durMicro = BeMotion.dur(BeMotion.SHORT4, reduce)
    val durMed = BeMotion.dur(BeMotion.MEDIUM2, reduce)
    val scheme = MaterialTheme.colorScheme

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = scheme.primaryContainer,
        contentColor = scheme.onPrimaryContainer,
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnimatedContent(
                    targetState = v.icon,
                    transitionSpec = {
                        (scaleIn(BeMotion.emphasized(durMicro), initialScale = 0.6f) +
                            fadeIn(tween(durMicro))) togetherWith
                            (scaleOut(BeMotion.emphasized(durMicro), targetScale = 0.6f) +
                                fadeOut(tween(durMicro)))
                    },
                    label = "statusIcon",
                ) { icon ->
                    Icon(
                        icon,
                        contentDescription = v.label,
                        modifier = Modifier.size(28.dp),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    AnimatedContent(
                        targetState = v.label,
                        transitionSpec = {
                            fadeIn(tween(durMed)) togetherWith fadeOut(tween(durMicro))
                        },
                        label = "statusLabel",
                    ) { label ->
                        Text(
                            label,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // 副行恒占一行（模型保证非空）：无信息时显示指引，卡片高度不塌陷
                    AnimatedContent(
                        targetState = v.detail,
                        transitionSpec = {
                            fadeIn(tween(durMed)) togetherWith fadeOut(tween(durMicro))
                        },
                        label = "statusDetail",
                    ) { detail ->
                        Text(
                            detail,
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onPrimaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

// ================= 网络事实 =================

@Composable
private fun NetworkFactsCard(
    state: UiState,
    ctx: android.content.Context,
) {
    val epoch by Repo.shizukuEpoch.collectAsState()
    val st = remember(epoch) { Privilege.detect(ctx, force = true) }
    val mode = Mode.fromId(Prefs.workMode)
    val modeLabel = when (mode) {
        Mode.STANDARD -> "标准"
        Mode.SHIZUKU -> "Shizuku"
        Mode.ROOT -> "Root"
    }
    val modeText = if (Privilege.modeReady(mode, st)) modeLabel else "$modeLabel · 未就绪"

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            FactRow("工作模式", modeText)
            FactRow(
                "当前网络",
                when {
                    !state.wifiOnline -> "未连接无线网络"
                    state.ssid != null -> state.ssid
                    state.ssidReadable -> "未获取"
                    else -> "已连接 · 未能读取名称"
                },
            )
            FactRow("IP 地址", if (!state.wifiOnline) "未获取" else state.ipv4?.ifBlank { null } ?: "未获取")

            // §3 权限拒绝内联说明：标准模式连着 WiFi 但读不到名 → 卡内说明后果，不全屏拦截
            val showPermNote = mode == Mode.STANDARD && state.wifiOnline && !state.ssidReadable
            val reduce = LocalReduceMotion.current
            AnimatedVisibility(
                visible = showPermNote,
                enter = fadeIn(tween(BeMotion.dur(BeMotion.MEDIUM2, reduce))) +
                    expandVertically(BeMotion.emphasized(BeMotion.dur(BeMotion.MEDIUM2, reduce))),
                exit = fadeOut(tween(BeMotion.dur(BeMotion.SHORT4, reduce))) +
                    shrinkVertically(BeMotion.emphasized(BeMotion.dur(BeMotion.MEDIUM2, reduce))),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (Prefs.ruleMode == RuleMode.WHITELIST && !Prefs.ssidFallbackAuth)
                            "未授予定位权限，读不到 WiFi 名；白名单下不会自动认证"
                        else "未授予定位权限，无法显示 WiFi 名；仍将尝试认证",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun FactRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ================= 账号 =================

// 分组行形状（首页式：首尾大圆角、行间细缝）见 ui/Group.kt

@Composable
private fun CurrentAccountCard(
    accounts: List<Account>,
    activeIdx: Int,
    onOpenSheet: () -> Unit,
    shape: Shape,
) {
    val a = accounts.getOrNull(activeIdx)
    Surface(
        onClick = onOpenSheet,
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        ListItem(
            headlineContent = { Text("账号") },
            supportingContent = {
                Text(
                    a?.let { it.note.ifBlank { it.username } } ?: "还未添加账号",
                    maxLines = 1,
                )
            },
            leadingContent = {
                Icon(
                    Icons.Outlined.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingContent = {
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = "管理账号",
                    tint = MaterialTheme.colorScheme.outline,
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountManageSheet(
    ctx: android.content.Context,
    accounts: List<Account>,
    activeIdx: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
    onChanged: () -> Unit,
) {
    var editing by remember { mutableStateOf<Int?>(null) }
    val snack = LocalSnackbar.current
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // 内容固定 60% 屏高，与账号数量无关；直接展开到位
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Text(
            "账号",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        Column(
            Modifier
                .fillMaxHeight(0.6f)
                .verticalScroll(rememberScrollState()),
        ) {
            if (accounts.isEmpty()) {
                Text(
                    "还没有账号，先添加一个",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
            }
            accounts.forEachIndexed { i, a ->
                AccountManageRow(
                    account = a,
                    isActive = i == activeIdx,
                    onSelect = { onSelect(i) },
                    onEdit = { editing = i },
                    onDelete = {
                        val snapshot = a
                        Prefs.removeAccount(i)
                        onChanged()
                        scope.launch {
                            val r = snack.showSnackbar("已删除 ${snapshot.username}", "撤销")
                            if (r == SnackbarResult.ActionPerformed) {
                                Prefs.addAccount(snapshot)
                                onChanged()
                            }
                        }
                    },
                )
            }
            ListItem(
                headlineContent = { Text("添加账号") },
                leadingContent = {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { editing = -1 },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            Spacer(Modifier.height(16.dp))
        }
    }

    val editingIdx = editing
    if (editingIdx != null) {
        AccountEditDialog(
            initial = accounts.getOrNull(editingIdx),
            others = accounts.filterIndexed { idx, _ -> idx != editingIdx },
            onDismiss = { editing = null },
            onSave = { a ->
                if (editingIdx == -1) {
                    Prefs.addAccount(a)
                    Prefs.setActive(Prefs.accountsSnapshot().size - 1)
                } else {
                    Prefs.updateAccount(editingIdx, a)
                }
                editing = null
                onChanged()
            },
        )
    }
}

@Composable
private fun AccountManageRow(
    account: Account,
    isActive: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(account.username) },
        supportingContent = {
            Text(
                UserAgents.label(account.userAgent) +
                    if (account.note.isNotBlank()) " · ${account.note}" else "",
                maxLines = 1,
            )
        },
        leadingContent = {
            Spacer(
                Modifier
                    .size(width = 3.dp, height = 36.dp)
                    .background(
                        color = if (isActive) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(2.dp),
                    ),
            )
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = "更多",
                        tint = MaterialTheme.colorScheme.outline,
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("编辑") }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("删除") }, onClick = { menu = false; onDelete() })
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AccountEditDialog(
    initial: Account?,
    others: List<Account>,
    onDismiss: () -> Unit,
    onSave: (Account) -> Unit,
) {
    // 编辑目标变化时重置表单状态（当前对话框每次打开都会离开组合、状态天然丢弃，
    // key 是防御：若将来改为常驻组合，避免把 A 账号的值带进 B 的编辑框）
    val editKey = initial?.username
    var username by remember(editKey) { mutableStateOf(initial?.username ?: "") }
    var password by remember(editKey) { mutableStateOf(initial?.password ?: "") }
    // UA 选择：下拉选项 id = "auto" | 预设 UA 值 | "custom"；非预设存量值落自定义框
    val initialUa = initial?.userAgent ?: UserAgents.AUTO
    var uaSel by remember(editKey) {
        mutableStateOf(if (initialUa.isEmpty() || UaOptions.any { it.id == initialUa }) initialUa else UaCustomId)
    }
    var uaCustom by remember(editKey) { mutableStateOf(if (uaSel == UaCustomId) initialUa else "") }
    var uaExpanded by remember(editKey) { mutableStateOf(false) }
    var note by remember(editKey) { mutableStateOf(initial?.note ?: "") }
    var showPw by remember(editKey) { mutableStateOf(false) }

    val trimmedUser = username.trim()
    val dup = others.any { it.username == trimmedUser }
    val trimmedCustom = uaCustom.trim()
    val valid = trimmedUser.isNotBlank() && password.isNotBlank() &&
        (!dup || trimmedUser == initial?.username) &&
        (uaSel != UaCustomId || UserAgents.isValidCustom(trimmedCustom))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "添加账号" else "编辑账号") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("用户名 / 学号 / 手机号") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                    isError = dup && trimmedUser != initial?.username,
                    supportingText = {
                        if (dup && trimmedUser != initial?.username) {
                            Text("已存在同名账号", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                    visualTransformation = if (showPw) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { showPw = !showPw }) { Text(if (showPw) "隐藏" else "显示") }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                // 认证通道：服务器按 UA 家族分派算法池（对齐 Esurfing-go-webui 全模式可选）
                ExposedDropdownMenuBox(
                    expanded = uaExpanded,
                    onExpandedChange = { uaExpanded = it },
                ) {
                    OutlinedTextField(
                        value = when {
                            uaSel == UaCustomId -> if (trimmedCustom.isEmpty()) "自定义…" else trimmedCustom
                            else -> UaOptions.first { it.id == uaSel }.label
                        },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("认证通道 / UA") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.large,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(uaExpanded) },
                        colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = uaExpanded,
                        onDismissRequest = { uaExpanded = false },
                    ) {
                        UaOptions.forEach { opt ->
                            DropdownMenuItem(
                                text = { Text(opt.label) },
                                onClick = {
                                    uaSel = opt.id
                                    uaExpanded = false
                                },
                            )
                        }
                    }
                }
                if (uaSel == UaCustomId) {
                    OutlinedTextField(
                        value = uaCustom,
                        onValueChange = { uaCustom = it },
                        label = { Text("自定义 UA") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.large,
                        supportingText = { Text("完整 UA 字符串，如 CCTP/WinSVR5/1068") },
                        isError = !UserAgents.isValidCustom(trimmedCustom),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else if (uaSel == UserAgents.AUTO) {
                    Text(
                        "被拒时自动回退并记住可用通道",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注 · 可选") },
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                enabled = valid,
                onClick = {
                    val ua = when (uaSel) {
                        UaCustomId -> trimmedCustom
                        else -> uaSel
                    }
                    onSave(Account(trimmedUser, password, ua, note.trim()))
                },
                shapes = ButtonDefaults.shapes(),
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text("取消") }
        },
    )
}

/** UA 下拉选项：id = "auto" | 预设 UA 值 | "custom" */
private data class UaOption(val id: String, val label: String)

private const val UaCustomId = "__custom__"

private val UaOptions = listOf(
    UaOption(UserAgents.AUTO, "自动（推荐：2104 → 2089 → 2093 回退）"),
    UaOption(UserAgents.ANDROID_2104, "安卓 2104 · 新代算法池"),
    UaOption(UserAgents.ANDROID_2089, "安卓 2089 · 旧代算法池"),
    UaOption(UserAgents.ANDROID_2093, "安卓 2093 · 最老学校兜底"),
    UaOption(UserAgents.PC, "PC 通道（Linux64/1003）"),
    UaOption(UaCustomId, "自定义…"),
)

// ================= 入口卡（设置 / 日志 / 关于） =================

@Composable
private fun HomeEntryCard(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    shape: Shape,
    supporting: (@Composable () -> Unit)? = null,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = supporting,
            leadingContent = {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            },
            trailingContent = {
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

@Composable
fun LogLineRow(l: LogLine, maxLines: Int = Int.MAX_VALUE, showTs: Boolean = false) {
    val ext = ExtTheme.colors
    val dotColor = when (l.level) {
        Mobile.LogError -> MaterialTheme.colorScheme.error
        Mobile.LogWarn -> ext.warning
        Mobile.LogInfo -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(
            Modifier
                .size(width = 3.dp, height = 14.dp)
                .background(
                    color = if (l.level == Mobile.LogError || l.level == Mobile.LogWarn) dotColor
                    else MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(2.dp),
                ),
        )
        Spacer(Modifier.width(8.dp))
        if (showTs) {
            Text(
                formatLogTs(l.ts),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            l.msg,
            style = MaterialTheme.typography.bodySmall,
            color = if (l.level == Mobile.LogError) MaterialTheme.colorScheme.error else textColor,
            maxLines = maxLines,
        )
    }
}

internal fun formatLogTs(ts: Long): String =
    java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(ts))
