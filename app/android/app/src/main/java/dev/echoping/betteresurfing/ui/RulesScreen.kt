package dev.echoping.betteresurfing.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.echoping.betteresurfing.ui.theme.BeMotion
import dev.echoping.betteresurfing.ui.theme.LocalReduceMotion
import dev.echoping.betteresurfing.net.NetWatch
import dev.echoping.betteresurfing.net.WifiState
import dev.echoping.betteresurfing.net.sanitizeSsid
import dev.echoping.betteresurfing.privilege.Mode
import dev.echoping.betteresurfing.privilege.Privilege
import dev.echoping.betteresurfing.store.Prefs
import dev.echoping.betteresurfing.store.RuleMode
import dev.echoping.betteresurfing.store.WifiRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen(onChanged: () -> Unit) {
    var mode by remember { mutableStateOf(Prefs.ruleMode) }
    var rules by remember { mutableStateOf(Prefs.rulesSnapshot()) }
    var adding by remember { mutableStateOf(false) }
    val wifi by NetWatch.state.collectAsState()

    fun refresh() {
        rules = Prefs.rulesSnapshot()
        onChanged()
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // 规则模式：与其他设置行一致的下拉选择样式
            GroupedColumn(
                listOf {
                    DropdownSettingRow(
                        label = "规则模式",
                        desc = when (mode) {
                            RuleMode.WHITELIST -> "仅在命中的 WiFi 下自动认证"
                            RuleMode.BLACKLIST -> "命中的 WiFi 静默，不发送探测包"
                            RuleMode.ALL -> "连接任何 WiFi 都自动认证"
                        },
                        options = listOf(
                            RuleMode.WHITELIST to "白名单",
                            RuleMode.BLACKLIST to "黑名单",
                            RuleMode.ALL to "全部认证",
                        ),
                        selected = mode,
                    ) { m ->
                        mode = m
                        Prefs.setRuleMode(m)
                        onChanged()
                    }
                },
            )

            // 空态：独立 Box 吃满剩余高度，内容真正垂直居中（LazyColumn item 不会撑满）
            if (rules.isEmpty()) {
                Box(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Outlined.Wifi,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(40.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "暂无规则",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (mode == RuleMode.ALL) "全部认证模式下规则列表不生效" else "点右下角「添加规则」",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            } else LazyColumn(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(bottom = 96.dp + navigationBarsBottom()),
            ) {
                itemsIndexed(rules) { i, r ->
                    var menu by remember { mutableStateOf(false) }
                    Surface(
                        shape = groupShape(i, rules.size),
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    ) {
                        ListItem(
                            headlineContent = { Text(r.value) },
                            supportingContent = {
                                Text((if (r.type == "bssid") "BSSID" else "SSID") + if (r.note.isNotBlank()) " · ${r.note}" else "")
                            },
                            leadingContent = {
                                Icon(
                                    if (r.type == "bssid") Icons.Outlined.Router else Icons.Outlined.Wifi,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            trailingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Switch(
                                        checked = r.enabled,
                                        onCheckedChange = { v -> Prefs.updateRule(i, r.copy(enabled = v)); refresh() },
                                    )
                                    BoxMenu(menu, { menu = it }, onDelete = { Prefs.removeRule(i); refresh() })
                                }
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = { adding = true },
            icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
            text = { Text("添加规则") },
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding(),
        )
    }

    if (adding) {
        AddRuleSheet(
            currentWifi = wifi,
            onDismiss = { adding = false },
            onSave = { rs ->
                // 批量加入，跳过已存在的同类型同值规则
                val existing = Prefs.rulesSnapshot().map { it.type to it.value }.toSet()
                rs.filter { (it.type to it.value) !in existing }.forEach { Prefs.addRule(it) }
                adding = false
                refresh()
            },
        )
    }
}

@Composable
private fun BoxMenu(expanded: Boolean, onExpanded: (Boolean) -> Unit, onDelete: () -> Unit) {
    androidx.compose.foundation.layout.Box {
        IconButton(onClick = { onExpanded(true) }) {
            Icon(Icons.Outlined.MoreVert, "更多")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpanded(false) }) {
            DropdownMenuItem(
                text = { Text("删除") },
                onClick = { onExpanded(false); onDelete() },
            )
        }
    }
}

@Composable
private fun SavedWifiHint(text: String, action: String, onRetry: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onRetry) { Text(action) }
    }
}
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AddRuleSheet(
    currentWifi: WifiState,
    onDismiss: () -> Unit,
    onSave: (List<WifiRule>) -> Unit,
) {
    val ctx = LocalContext.current
    val currentSsid = sanitizeSsid(currentWifi.ssid)

    // 已保存 WiFi 列表：Android 10+ 标准模式禁止读取，仅 Shizuku(shell)/Root 可读系统完整列表。
    // 打开时强制重新检测特权（授权可能刚在 Shizuku 应用内变更，缓存会过期），失败可重试。
    val workMode = Mode.fromId(Prefs.workMode)
    var saved by remember { mutableStateOf<List<String>?>(null) }
    var savedModeReady by remember { mutableStateOf(true) }
    var savedReadable by remember { mutableStateOf(true) }
    var savedRetry by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var manualInput by remember { mutableStateOf(false) }
    if (workMode != Mode.STANDARD) {
        LaunchedEffect(savedRetry) {
            saved = null
            val (ready, ok, list) = withContext(Dispatchers.IO) {
                val st = Privilege.detect(ctx, force = true)
                val isReady = (workMode == Mode.SHIZUKU && st.shizukuReady) || (workMode == Mode.ROOT && st.rootAvailable)
                if (!isReady) {
                    dev.echoping.betteresurfing.engine.Repo.onLog(2, "读取已保存 WiFi 跳过：$workMode 模式未就绪")
                    Triple(false, false, emptyList<String>())
                } else {
                    val r = Privilege.readSavedWifi()
                    Triple(true, r.first, r.second)
                }
            }
            savedModeReady = ready
            savedReadable = ok
            saved = list
        }
    }

    // 当前连接与系统已保存合并为一个可选列表
    val listItems: List<String> = buildList {
        if (currentWifi.ssidReadable && currentSsid != null) add(currentSsid)
        if (workMode != Mode.STANDARD && savedReadable) saved?.let(::addAll)
    }.distinct()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        // 外层不滚动：标题/说明/按钮固定，仅多选列表在 weight 有界内滚（避免嵌套滚动）
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("添加规则", style = MaterialTheme.typography.titleLarge)

            // 说明：分组卡片缺个开头时先垫一句用途
            Text(
                "命中以下 WiFi 时按上方模式自动认证",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when {
                workMode != Mode.STANDARD && !savedModeReady ->
                    SavedWifiHint("工作模式未就绪，无法读取已保存 WiFi", "重新检测") { savedRetry++ }
                workMode != Mode.STANDARD && saved == null -> {
                    Text(
                        "正在读取已保存的 WiFi",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                    LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 4.dp))
                }
                workMode != Mode.STANDARD && !savedReadable ->
                    SavedWifiHint("读取失败 · 详情见运行日志", "重试") { savedRetry++ }
                listItems.isEmpty() -> Text(
                    if (!currentWifi.connected) "当前未连接无线网络，也无已保存列表"
                    else "当前 WiFi 名称不可读，标准模式需定位权限",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }

            if (listItems.isNotEmpty()) {
                // 多选列表：选中整卡按 secondaryContainer 染色的 MD3E 多选组
                Column(
                    Modifier.weight(1f, fill = false).heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    listItems.forEachIndexed { i, s ->
                        val isCurrent = currentWifi.connected && s == currentSsid
                        val checked = s in selected
                        val container by animateColorAsState(
                            if (checked) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerHigh,
                            BeMotion.emphasized(BeMotion.dur(BeMotion.SHORT4, LocalReduceMotion.current)),
                            label = "wifiSelBg",
                        )
                        Surface(
                            onClick = { selected = if (checked) selected - s else selected + s },
                            shape = groupShape(i, listItems.size),
                            color = container,
                        ) {
                            ListItem(
                                headlineContent = { Text(s) },
                                supportingContent = if (isCurrent) { { Text("当前连接") } } else null,
                                leadingContent = {
                                    Icon(
                                        Icons.Outlined.Wifi,
                                        null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                trailingContent = {
                                    Checkbox(checked = checked, onCheckedChange = null)
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            )
                        }
                    }
                }
                if (workMode != Mode.STANDARD) {
                    Text(
                        "多选后一键保存",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }

            // 手动输入：独立入口卡，点开对话框
            Surface(
                onClick = { manualInput = true },
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                ListItem(
                    headlineContent = { Text("手动输入") },
                    supportingContent = { Text("SSID / BSSID，自动识别类型") },
                    leadingContent = {
                        Icon(
                            Icons.Outlined.Edit,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }

            // MD3E 连接按钮组：全宽并排，选中数带进保存按钮
            ButtonGroup(Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss, Modifier.weight(1f)) { Text("取消") }
                Button(
                    enabled = selected.isNotEmpty(),
                    onClick = { onSave(selected.map { WifiRule("ssid", it) }) },
                    modifier = Modifier.weight(1f),
                ) { Text(if (selected.isEmpty()) "保存" else "保存 ${selected.size} 项") }
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    if (manualInput) {
        ManualRuleDialog(
            onDismiss = { manualInput = false },
            onSave = { r -> manualInput = false; onSave(listOf(r)) },
        )
    }
}

/**
 * 手动输入对话框：按值自动识别 SSID / BSSID，无需手选类型。
 * MD3E：两输入框用 default 大圆角，通配符提示移到备注下方；保存按钮为填充按钮。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ManualRuleDialog(onDismiss: () -> Unit, onSave: (WifiRule) -> Unit) {
    var value by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val trimmed = value.trim()
    val isBssid = trimmed.matches(Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$"))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("手动输入") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("值") },
                    supportingText = {
                        if (trimmed.isNotBlank()) {
                            Text(if (isBssid) "识别为 BSSID" else "识别为 SSID")
                        }
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注 · 可选") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "支持通配符 * ?，MAC 地址格式视为 BSSID",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                enabled = trimmed.isNotBlank(),
                onClick = { onSave(WifiRule(if (isBssid) "bssid" else "ssid", trimmed, note.trim())) },
                shapes = ButtonDefaults.shapes(),
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text("取消") }
        },
    )
}
