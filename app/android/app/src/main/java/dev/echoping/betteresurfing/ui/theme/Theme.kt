package dev.echoping.betteresurfing.ui.theme

import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.echoping.betteresurfing.store.Prefs

/*
 * 主题系统：MD3 Expressive。
 *
 * 内置 5 套 TonalSpot 配色（seed 由 Theme Builder 反推，保证 primary 等角色
 * 与 Theme Builder 输出一致），外加 Android 12+ 动态取色与 AMOLED 纯黑。
 * 全部配色（含动态）共用同一套语义扩展色（Success/Warning），保证状态可辨识。
 */

// ================= 配色定义 =================

enum class ThemeMode(val id: String, val label: String) {
    SYSTEM("system", "跟随系统"),
    LIGHT("light", "浅色"),
    DARK("dark", "深色"),
}

/** 内置 TonalSpot 配色：seed + 名称 + 预览点（由 Hct 引擎生成）。 */
data class ThemePalette(
    val id: String,
    val label: String,
    val seedArgb: Int,
    /** 预览三色：primary40 / tertiary40 / surfaceVariant（浅色）。 */
    val previewColors: Triple<Color, Color, Color>,
)

private val DEFAULT_PALETTE = ThemePalette(
    id = "brand",
    label = "默认蓝",
    seedArgb = 0xFF1565C0.toInt(),
    previewColors = Triple(Color(0xFF005DB5), Color(0xFF6F5675), Color(0xFFE0E2EC)),
)

val BuiltInPalettes = listOf(
    DEFAULT_PALETTE,
    ThemePalette(
        id = "teal",
        label = "青水",
        seedArgb = 0xFF00696B.toInt(),
        previewColors = Triple(Color(0xFF006A6B), Color(0xFF4A6364), Color(0xFFDDE4E4)),
    ),
    ThemePalette(
        id = "violet",
        label = "紫藤",
        seedArgb = 0xFF6750A4.toInt(),
        previewColors = Triple(Color(0xFF6750A4), Color(0xFF735686), Color(0xFFE7E0EC)),
    ),
    ThemePalette(
        id = "orange",
        label = "落霞",
        seedArgb = 0xFFA43D00.toInt(),
        previewColors = Triple(Color(0xFFA43D00), Color(0xFF775637), Color(0xFFF3DFD2)),
    ),
    ThemePalette(
        id = "pink",
        label = "桃粉",
        seedArgb = 0xFFB4135E.toInt(),
        previewColors = Triple(Color(0xFFB4135E), Color(0xFF7A5066), Color(0xFFF0DEE2)),
    ),
)

const val DYNAMIC_PALETTE_ID = "dynamic"

// ================= 主题设置（Compose 可观察，写入即持久化到 Prefs） =================

object ThemeController {
    private val _mode = mutableStateOf(ThemeMode.SYSTEM)
    private val _paletteId = mutableStateOf(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) DYNAMIC_PALETTE_ID else DEFAULT_PALETTE.id
    )
    private val _amoled = mutableStateOf(false)

    val mode: ThemeMode get() = _mode.value
    val paletteId: String get() = _paletteId.value
    val amoled: Boolean get() = _amoled.value

    private var loaded = false

    /** 从 Prefs 恢复设置（Application.onCreate 已调 Prefs.init）。 */
    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        _mode.value = ThemeMode.entries.firstOrNull { it.id == Prefs.themeMode } ?: ThemeMode.SYSTEM
        _paletteId.value = Prefs.themePalette
        _amoled.value = Prefs.themeAmoled
    }

    fun setMode(v: ThemeMode) {
        if (mode == v) return
        _mode.value = v
        Prefs.setThemeMode(v.id)
    }

    fun setPalette(v: String) {
        if (paletteId == v) return
        _paletteId.value = v
        Prefs.setThemePalette(v)
    }

    fun setAmoled(v: Boolean) {
        if (amoled == v) return
        _amoled.value = v
        Prefs.setThemeAmoled(v)
    }
}

// ================= 色板生成（Hct → ColorScheme） =================

private object SchemeGen {
    /** 由 seed 生成浅色 ColorScheme（TonalSpot）。 */
    fun light(seedArgb: Int): androidx.compose.material3.ColorScheme {
        val p = Hct.TonalSpot(seedArgb)
        return lightColorScheme(
            primary = Color(p.primary.tone(40)),
            onPrimary = Color(p.primary.tone(100)),
            primaryContainer = Color(p.primary.tone(90)),
            onPrimaryContainer = Color(p.primary.tone(10)),
            secondary = Color(p.secondary.tone(40)),
            onSecondary = Color(p.secondary.tone(100)),
            secondaryContainer = Color(p.secondary.tone(90)),
            onSecondaryContainer = Color(p.secondary.tone(10)),
            tertiary = Color(p.tertiary.tone(40)),
            onTertiary = Color(p.tertiary.tone(100)),
            tertiaryContainer = Color(p.tertiary.tone(90)),
            onTertiaryContainer = Color(p.tertiary.tone(10)),
            error = Color(p.error.tone(40)),
            onError = Color(p.error.tone(100)),
            errorContainer = Color(p.error.tone(90)),
            onErrorContainer = Color(p.error.tone(10)),
            background = Color(p.neutral.tone(98)),
            onBackground = Color(p.neutral.tone(10)),
            surface = Color(p.neutral.tone(98)),
            onSurface = Color(p.neutral.tone(10)),
            surfaceVariant = Color(p.neutralVariant.tone(90)),
            onSurfaceVariant = Color(p.neutralVariant.tone(30)),
            outline = Color(p.neutralVariant.tone(50)),
            outlineVariant = Color(p.neutralVariant.tone(80)),
            inverseSurface = Color(p.neutral.tone(20)),
            inverseOnSurface = Color(p.neutral.tone(95)),
            inversePrimary = Color(p.primary.tone(80)),
            scrim = Color(p.neutral.tone(0)),
            surfaceTint = Color(p.primary.tone(40)),
            surfaceDim = Color(p.neutral.tone(87)),
            surfaceBright = Color(p.neutral.tone(98)),
            surfaceContainerLowest = Color(p.neutral.tone(100)),
            surfaceContainerLow = Color(p.neutral.tone(96)),
            surfaceContainer = Color(p.neutral.tone(94)),
            surfaceContainerHigh = Color(p.neutral.tone(92)),
            surfaceContainerHighest = Color(p.neutral.tone(90)),
        )
    }

    /** 由 seed 生成深色 ColorScheme（TonalSpot）。 */
    fun dark(seedArgb: Int): androidx.compose.material3.ColorScheme {
        val p = Hct.TonalSpot(seedArgb)
        return darkColorScheme(
            primary = Color(p.primary.tone(80)),
            onPrimary = Color(p.primary.tone(20)),
            primaryContainer = Color(p.primary.tone(30)),
            onPrimaryContainer = Color(p.primary.tone(90)),
            secondary = Color(p.secondary.tone(80)),
            onSecondary = Color(p.secondary.tone(20)),
            secondaryContainer = Color(p.secondary.tone(30)),
            onSecondaryContainer = Color(p.secondary.tone(90)),
            tertiary = Color(p.tertiary.tone(80)),
            onTertiary = Color(p.tertiary.tone(20)),
            tertiaryContainer = Color(p.tertiary.tone(30)),
            onTertiaryContainer = Color(p.tertiary.tone(90)),
            error = Color(p.error.tone(80)),
            onError = Color(p.error.tone(20)),
            errorContainer = Color(p.error.tone(30)),
            onErrorContainer = Color(p.error.tone(90)),
            background = Color(p.neutral.tone(6)),
            onBackground = Color(p.neutral.tone(90)),
            surface = Color(p.neutral.tone(6)),
            onSurface = Color(p.neutral.tone(90)),
            surfaceVariant = Color(p.neutralVariant.tone(30)),
            onSurfaceVariant = Color(p.neutralVariant.tone(80)),
            outline = Color(p.neutralVariant.tone(60)),
            outlineVariant = Color(p.neutralVariant.tone(30)),
            inverseSurface = Color(p.neutral.tone(90)),
            inverseOnSurface = Color(p.neutral.tone(20)),
            inversePrimary = Color(p.primary.tone(40)),
            scrim = Color(p.neutral.tone(0)),
            surfaceTint = Color(p.primary.tone(80)),
            surfaceDim = Color(p.neutral.tone(6)),
            surfaceBright = Color(p.neutral.tone(24)),
            surfaceContainerLowest = Color(p.neutral.tone(4)),
            surfaceContainerLow = Color(p.neutral.tone(10)),
            surfaceContainer = Color(p.neutral.tone(12)),
            surfaceContainerHigh = Color(p.neutral.tone(17)),
            surfaceContainerHighest = Color(p.neutral.tone(22)),
        )
    }
}

// ================= 语义扩展色（Success / Warning） =================

/**
 * MD3 官方推荐的语义色扩展方式：Color roles beyond the baseline。
 * 在线=Success、认证中=Warning、离线/未启动=中性、异常=error。
 * 深浅两套各自校准，动态取色模式下也叠加使用（不随壁纸漂移，保证状态可辨识）。
 */
@Immutable
data class ExtendedColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
)

private val LightExtendedColors = ExtendedColors(
    success = Color(0xFF1B6D24), onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFFA3F69C), onSuccessContainer = Color(0xFF003909),
    warning = Color(0xFF845400), onWarning = Color(0xFFFFFFFF),
    warningContainer = Color(0xFFFFDDB6), onWarningContainer = Color(0xFF462A00),
)

private val DarkExtendedColors = ExtendedColors(
    success = Color(0xFF88D982), onSuccess = Color(0xFF003909),
    successContainer = Color(0xFF005312), onSuccessContainer = Color(0xFFA3F69C),
    warning = Color(0xFFFDB95F), onWarning = Color(0xFF462A00),
    warningContainer = Color(0xFF643F00), onWarningContainer = Color(0xFFFFDDB6),
)

val LocalExtendedColors = staticCompositionLocalOf {
    ExtendedColors(
        success = Color.Unspecified, onSuccess = Color.Unspecified,
        successContainer = Color.Unspecified, onSuccessContainer = Color.Unspecified,
        warning = Color.Unspecified, onWarning = Color.Unspecified,
        warningContainer = Color.Unspecified, onWarningContainer = Color.Unspecified,
    )
}

/** 系统「移除动画」：时长归 1ms，转场只保留淡入淡出。 */
val LocalReduceMotion = staticCompositionLocalOf { false }

// ================= 形状（MD3 Expressive 加大圆角） =================

/** extraLarge 首页卡片；large 设置分组；medium 输入；Chip/FAB 走组件全圆角 */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

// ================= 主题组合 =================

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BetterEsurfingTheme(content: @Composable () -> Unit) {
    ThemeController.ensureLoaded()
    val dark = when (ThemeController.mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val ctx = LocalContext.current
    // 只认 ANIMATOR_DURATION_SCALE：它是无障碍「移除动画」的统一信号（开启会把三个 scale 全打 0）；
    // 不能看 WINDOW/TRANSITION_ANIMATION_SCALE——那是开发者选项里管窗口/窗口切换动画的倍率，
    // 很多用户（如本机 HyperOS）只把这两项关 0 求系统转场更快，此时应用内部动画不应被连坐关掉。
    val reduceMotion = remember {
        try {
            Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        } catch (_: Exception) { false }
    }

    val colorScheme = remember(dark, ThemeController.paletteId, ThemeController.amoled) {
        val dynamic = ThemeController.paletteId == DYNAMIC_PALETTE_ID &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val base = if (dynamic) {
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        } else {
            val palette = BuiltInPalettes.firstOrNull { it.id == ThemeController.paletteId }
                ?: DEFAULT_PALETTE
            if (dark) SchemeGen.dark(palette.seedArgb) else SchemeGen.light(palette.seedArgb)
        }
        if (dark && ThemeController.amoled) {
            base.copy(
                background = Color.Black,
                surface = Color.Black,
                surfaceDim = Color.Black,
            )
        } else base
    }
    val extended = if (dark) DarkExtendedColors else LightExtendedColors

    CompositionLocalProvider(
        LocalExtendedColors provides extended,
        LocalReduceMotion provides reduceMotion,
    ) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = MotionScheme.expressive(),
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}

/** 语义色便捷访问 */
object ExtTheme {
    val colors: ExtendedColors
        @Composable get() = LocalExtendedColors.current
}
