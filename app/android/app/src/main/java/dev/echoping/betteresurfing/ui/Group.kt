package dev.echoping.betteresurfing.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * 首页式分组（行间细缝）：每行独立 surfaceContainerLowest 卡片，
 * 行间 2dp；首行上大角/末行下大角（跟随主题 extraLarge），中间行小角。
 */

@Composable
fun groupFirstShape(): Shape = MaterialTheme.shapes.extraLarge.copy(
    bottomStart = CornerSize(4.dp), bottomEnd = CornerSize(4.dp),
)

@Composable
fun groupMidShape(): Shape = RoundedCornerShape(4.dp)

@Composable
fun groupLastShape(): Shape = MaterialTheme.shapes.extraLarge.copy(
    topStart = CornerSize(4.dp), topEnd = CornerSize(4.dp),
)

@Composable
fun groupShape(index: Int, count: Int): Shape = when {
    count == 1 -> MaterialTheme.shapes.extraLarge
    index == 0 -> groupFirstShape()
    index == count - 1 -> groupLastShape()
    else -> groupMidShape()
}

/** 分组容器：rows 逐个包进独立卡片并按位置套形状，行间 2dp 细缝。 */
@Composable
fun GroupedColumn(
    rows: List<@Composable () -> Unit>,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEachIndexed { i, row ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                shape = groupShape(i, rows.size),
                modifier = Modifier.fillMaxWidth(),
            ) { row() }
        }
    }
}

/**
 * 设置下拉选择行：左侧标题/说明，右侧当前值 + 下拉箭头，整行点按展开菜单。
 * 菜单项选中态带对勾，样式与 SwitchRow 等设置行一致。
 */
@Composable
fun <T> DropdownSettingRow(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    desc: String = "",
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = options.firstOrNull { it.first == selected }?.second.orEmpty()
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = { if (desc.isNotBlank()) Text(desc) },
        trailingContent = {
            // 下拉菜单锚定在右侧当前值上，弹窗从右侧展开
            Box {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        currentLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Icon(
                        Icons.Outlined.ArrowDropDown,
                        contentDescription = "展开选项",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    options.forEach { (value, text) ->
                        DropdownMenuItem(
                            text = { Text(text) },
                            leadingIcon = {
                                if (value == selected) Icon(Icons.Outlined.Check, contentDescription = null)
                                else Spacer(Modifier.size(24.dp))
                            },
                            onClick = {
                                expanded = false
                                onSelect(value)
                            },
                        )
                    }
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { expanded = true },
    )
}
