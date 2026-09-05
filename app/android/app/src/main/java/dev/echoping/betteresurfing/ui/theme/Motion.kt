package dev.echoping.betteresurfing.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween

/**
 * MD3 Motion token（m3.material.io/styles/motion）。
 * 页面代码禁止随手 tween(150/900)；时长一律经 [dur] 以尊重「移除动画」。
 */
object BeMotion {
    const val SHORT4 = 200
    const val MEDIUM2 = 300
    const val MEDIUM4 = 400
    const val LONG2 = 500

    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    fun dur(base: Int, reduce: Boolean): Int = if (reduce) 1 else base

    fun <T> emphasized(duration: Int = MEDIUM2): FiniteAnimationSpec<T> =
        tween(duration, easing = Emphasized)
}
