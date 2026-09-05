package dev.echoping.betteresurfing.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation3.scene.Scene
import androidx.navigationevent.NavigationEvent
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * SukiSU Ultra 同款导航转场：水平平移 + 1/4 视差。
 *
 * 参数逐行取自 SukiSU 实际使用的 miuix-navigation3-ui 0.9.3 fork
 * （包名同为 androidx.navigation3.ui，是 navigation3-ui 的 fork）的
 * defaultTransitionSpec / defaultPopTransitionSpec /
 * defaultPredictivePopTransitionSpec 与 NavTransitionEasing，替换
 * androidx 官方默认的 fade(700ms) / scaleOut(0.7)。
 *
 * 几何语义（宽度 = it，全屏宽）：
 * - 前进：新页 initialOffsetX=+it 从右整宽滑入；旧页 targetOffsetX=-it/4 向左让位 1/4。
 * - 返回：上一页 initialOffsetX=-it/4 从左 1/4 滑入；当前页 targetOffsetX=+it 向右整宽滑出。
 * - 预测性返回：与返回同几何，LinearEasing（手势进度线性映射，由 NavDisplay 的
 *   SeekableTransitionState.seekTo 驱动），松手 commit/取消按剩余时长收尾。
 *
 * 已知差异（fork 内部效果，公开 API 无法注入，属 MIUI 视觉装饰）：
 * 转场期间顶层页面按设备圆角做 squircle 裁剪、底层页面 0.5 黑色压暗、
 * 非目标页面输入拦截。此处均未实现，仅保留核心滑动几何与曲线。
 */

/** miuix NavTransitionEasing(response=0.8f, damping=0.95f)：物理阻尼振荡曲线，照抄实现。 */
private class NavTransitionEasing(response: Float, damping: Float) : Easing {
    private val r: Float
    private val w: Float
    private val c2: Float

    init {
        val omega = 2.0 * PI / response
        val k = omega * omega
        val c = damping * 4.0 * PI / response

        w = (sqrt(4.0 * k - c * c) / 2.0).toFloat()
        r = (-c / 2.0).toFloat()
        c2 = r / w
    }

    override fun transform(fraction: Float): Float {
        val t = fraction.toDouble()
        val decay = exp(r * t)
        return (decay * (-cos(w * t) + c2 * sin(w * t)) + 1.0).toFloat()
    }
}

/** miuix NavAnimationEasing（fork 内为 private，同值复刻）。 */
private val NavAnimationEasing = NavTransitionEasing(0.8f, 0.95f)

/** 前进：新页右入整宽，旧页左移 1/4 视差。500ms + NavAnimationEasing。 */
fun <T : Any> beTransitionSpec(): AnimatedContentTransitionScope<Scene<T>>.() -> ContentTransform = {
    ContentTransform(
        slideInHorizontally(
            initialOffsetX = { it },
            animationSpec = tween(durationMillis = 500, easing = NavAnimationEasing),
        ),
        slideOutHorizontally(
            targetOffsetX = { -it / 4 },
            animationSpec = tween(durationMillis = 500, easing = NavAnimationEasing),
        ),
    )
}

/** 返回（含未开预测手势时的传统返回）：上一页左入 1/4，当前页右出整宽。500ms。 */
fun <T : Any> bePopTransitionSpec(): AnimatedContentTransitionScope<Scene<T>>.() -> ContentTransform = {
    ContentTransform(
        slideInHorizontally(
            initialOffsetX = { -it / 4 },
            animationSpec = tween(durationMillis = 500, easing = NavAnimationEasing),
        ),
        slideOutHorizontally(
            targetOffsetX = { it },
            animationSpec = tween(durationMillis = 500, easing = NavAnimationEasing),
        ),
    )
}

/** 预测性返回：同返回几何，LinearEasing 550ms（手势进度驱动）。swipeEdge 忽略（SukiSU 默认同）。 */
fun <T : Any> bePredictivePopTransitionSpec():
        AnimatedContentTransitionScope<Scene<T>>.(@NavigationEvent.SwipeEdge Int) -> ContentTransform =
    { _ ->
        ContentTransform(
            slideInHorizontally(
                initialOffsetX = { -it / 4 },
                animationSpec = tween(durationMillis = 550, easing = LinearEasing),
            ),
            slideOutHorizontally(
                targetOffsetX = { it },
                animationSpec = tween(durationMillis = 550, easing = LinearEasing),
            ),
        )
    }
