package dev.echoping.betteresurfing.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.echoping.be.mobile.Mobile
import dev.echoping.betteresurfing.engine.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 日志等级筛选：显示 level >= minLevel 的日志 */
private data class LogFilter(val label: String, val minLevel: Int)

private val LogFilters = listOf(
    LogFilter("全部", -1),
    LogFilter("信息", Mobile.LogInfo.toInt()),
    LogFilter("警告", Mobile.LogWarn.toInt()),
    LogFilter("错误", Mobile.LogError.toInt()),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(onBack: () -> Unit = {}) {
    val ctx = LocalContext.current
    val logs by Repo.logs.collectAsState()
    var minLevel by rememberSaveable { mutableIntStateOf(-1) }
    val filtered = remember(logs, minLevel) {
        if (minLevel < 0) logs else logs.filter { it.level >= minLevel }
    }
    val listState = rememberLazyListState()
    val snack = LocalSnackbar.current
    val scope = rememberCoroutineScope()

    // 「保存日志」：SAF 让用户选落盘位置，写入当前完整日志（导出已由引擎侧脱敏）
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(Repo.dumpLogs().toByteArray(Charsets.UTF_8))
                    } ?: error("open output stream failed")
                }.isSuccess
            }
            snack.showSnackbar(if (ok) "日志已保存" else "日志保存失败")
        }
    }

    LaunchedEffect(filtered.size) {
        if (filtered.isNotEmpty()) listState.animateScrollToItem(filtered.size - 1)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        topBar = {
            TopAppBar(
                title = { Text("运行日志") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                            .format(java.util.Date())
                        saveLauncher.launch("betteres-log-$stamp.txt")
                    }) { Icon(Icons.Outlined.SaveAlt, "保存日志到文件") }
                    IconButton(onClick = {
                        Repo.clearLogs()
                        scope.launch { snack.showSnackbar("日志已清空") }
                    }) { Icon(Icons.Outlined.Delete, "清空") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Column(Modifier.scaffoldEdgeToEdgePadding(pad).fillMaxSize()) {
            // 等级筛选（芯片行，可横滑）
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LogFilters.forEach { f ->
                    val sel = minLevel == f.minLevel
                    FilterChip(
                        selected = sel,
                        onClick = { minLevel = f.minLevel },
                        label = { Text(f.label) },
                        leadingIcon = if (sel) {
                            {
                                Icon(
                                    Icons.Outlined.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                                )
                            }
                        } else null,
                    )
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp + navigationBarsBottom()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (filtered.isEmpty()) {
                    item {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 56.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                Icons.Outlined.Terminal,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(44.dp),
                            )
                            Text(
                                if (logs.isEmpty()) "启动后将显示引擎事件" else "当前筛选下暂无日志",
                                color = MaterialTheme.colorScheme.outline,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                items(filtered.size) { i ->
                    Surface(
                        // 不随文本长度变化，整列统一满宽
                        modifier = Modifier.fillMaxWidth(),
                        shape = groupShape(i, filtered.size),
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                    ) {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                            LogLineRow(filtered[i], showTs = true)
                        }
                    }
                }
            }
        }
    }
}
