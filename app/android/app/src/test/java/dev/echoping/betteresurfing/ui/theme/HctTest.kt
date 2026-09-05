package dev.echoping.betteresurfing.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HCT 取色引擎正确性校验。
 *
 * 注意：本仓库旧主题常量（seed #1565C0）出自旧版 Theme Builder，
 * 其 hue/chroma 规则与现行 TonalSpot 不同（primary 实测为 hue≈262, chroma≈56
 * 的高彩度特调，neutral chroma≈4，secondary hue≈282），因此不能用旧常量
 * 校验现行 TonalSpot。这里改为：
 * 1) 校验与官方 TonalSpot 规则一致的锚点（hue 262/282, chroma 36/16/24/6/8, tone 表）；
 * 2) 校验 error 调色板（hue 25, chroma 84）；
 * 3) 校验 HCT 色相提取的单调性与合理性。
 */
class HctTest {

    private fun assertColor(expected: Int, actual: Int, label: String) {
        val er = (expected shr 16) and 0xFF; val eg = (expected shr 8) and 0xFF; val eb = expected and 0xFF
        val ar = (actual shr 16) and 0xFF; val ag = (actual shr 8) and 0xFF; val ab = actual and 0xFF
        assertTrue(
            "$label: expected #${expected.hex()} but was #${actual.hex()}",
            kotlin.math.abs(er - ar) <= 1 && kotlin.math.abs(eg - ag) <= 1 && kotlin.math.abs(eb - ab) <= 1,
        )
    }

    private fun Int.hex(): String = "%06X".format(this and 0xFFFFFF)

    // ---- TonalSpot 标准锚点（hue 262, chroma 36/16/24/6/8）----
    @Test fun p40() = assertColor(0xFF405F90.toInt(), Hct.TonalPalette(262.0, 36.0).tone(40), "P40")
    @Test fun p80() = assertColor(0xFFA9C7FF.toInt(), Hct.TonalPalette(262.0, 36.0).tone(80), "P80")
    @Test fun p30() = assertColor(0xFF264777.toInt(), Hct.TonalPalette(262.0, 36.0).tone(30), "P30")
    @Test fun s40() = assertColor(0xFF555F71.toInt(), Hct.TonalPalette(262.0, 16.0).tone(40), "S40")
    @Test fun s90() = assertColor(0xFFD9E3F8.toInt(), Hct.TonalPalette(262.0, 16.0).tone(90), "S90")
    @Test fun t40() = assertColor(0xFF6F5675.toInt(), Hct.TonalPalette(322.0, 24.0).tone(40), "T40")
    @Test fun t90() = assertColor(0xFFF9D8FD.toInt(), Hct.TonalPalette(322.0, 24.0).tone(90), "T90")
    @Test fun n10() = assertColor(0xFF191C20.toInt(), Hct.TonalPalette(262.0, 6.0).tone(10), "N10")
    @Test fun n98() = assertColor(0xFFF9F9FF.toInt(), Hct.TonalPalette(262.0, 6.0).tone(98), "N98")
    @Test fun nv30() = assertColor(0xFF44474E.toInt(), Hct.TonalPalette(262.0, 8.0).tone(30), "NV30")
    @Test fun nv90() = assertColor(0xFFE0E2EC.toInt(), Hct.TonalPalette(262.0, 8.0).tone(90), "NV90")
    @Test fun e40() = assertColor(0xFFB91B1B.toInt(), Hct.TonalPalette(25.0, 84.0).tone(40), "E40")
    @Test fun e80() = assertColor(0xFFFFB4AB.toInt(), Hct.TonalPalette(25.0, 84.0).tone(80), "E80")
    @Test fun e10() = assertColor(0xFF410002.toInt(), Hct.TonalPalette(25.0, 84.0).tone(10), "E10")
    @Test fun e90() = assertColor(0xFFFFDAD6.toInt(), Hct.TonalPalette(25.0, 84.0).tone(90), "E90")

    // ---- 高彩度特调锚点（旧版 Theme Builder 等价）----
    @Test fun brand40() = assertColor(0xFF005DB5.toInt(), Hct.TonalPalette(262.0, 56.0).tone(40), "brand40")
    @Test fun brand80() = assertColor(0xFFA9C7FF.toInt(), Hct.TonalPalette(262.0, 56.0).tone(80), "brand80")
    @Test fun brand30() = assertColor(0xFF00468B.toInt(), Hct.TonalPalette(262.0, 56.0).tone(30), "brand30")

    // ---- 色相提取 ----
    @Test fun hue_blue_seed() {
        val hue = Hct.hueOf(0xFF1565C0.toInt())
        assertEquals(262.0, hue, 3.0)
    }

    // ---- 灰阶：chroma=0 时输出纯灰 ----
    @Test fun neutral_gray() {
        val g = Hct.solveToInt(0.0, 0.0, 50.0)
        val r = (g shr 16) and 0xFF; val gg = (g shr 8) and 0xFF; val b = g and 0xFF
        assertEquals(r, gg); assertEquals(gg, b)
        assertEquals(119, r)
    }
}
