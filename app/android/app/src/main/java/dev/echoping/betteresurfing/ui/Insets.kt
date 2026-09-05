package dev.echoping.betteresurfing.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Edge-to-edge 辅助。
 *
 * Android 10+ 的 enableEdgeToEdge 默认 auto 导航栏样式会开启系统对比度遮罩
 * （isNavigationBarContrastEnforced=true），Android 15 强制 edge-to-edge 后系统
 * 在小白条区域铺一层近白色 scrim，看起来像白色导航栏背景；MainActivity 已在
 * onCreate 里将其关闭。
 *
 * Scaffold 的 contentWindowInsets 保持默认（FAB、顶栏位置依赖它），内容视口改用
 * scaffoldEdgeToEdgePadding 只避开顶栏与横向安全区，底部让滚动内容延伸到小白条
 * 下方；各滚动内容末尾用 navigationBarsBottom() 垫高，保证末项能完整滚出小白条。
 */

/** Scaffold 内容视口：只避开顶栏（含状态栏）与横向安全区，底部不避让导航栏。 */
@Composable
fun Modifier.scaffoldEdgeToEdgePadding(pad: PaddingValues): Modifier {
    val dir = LocalLayoutDirection.current
    return padding(
        top = pad.calculateTopPadding(),
        start = pad.calculateStartPadding(dir),
        end = pad.calculateEndPadding(dir),
    )
}

/** 滚动内容末尾底衬：导航栏高度 + extra 间距，保证末项能滚出小白条。 */
@Composable
fun navigationBarsBottom(extra: Dp = 0.dp): Dp =
    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + extra
