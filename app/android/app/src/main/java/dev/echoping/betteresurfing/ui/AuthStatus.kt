package dev.echoping.betteresurfing.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiFind
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.ui.graphics.vector.ImageVector
import dev.echoping.be.mobile.Mobile
import dev.echoping.betteresurfing.engine.EngineHolder
import dev.echoping.betteresurfing.engine.UiState

/**
 * 状态呈现模型：首页状态条与前台通知共用同一来源，两处文案/图标完全一致。
 *
 * 主词与副行语义分层，终结「上面一套词、下面引擎 detail 又一套词」的不一致：
 * - [label] 主状态词 —— 用户需要一眼知道的事（已在线 / 认证中… / 未启动…）；
 * - [detail] 副行 —— 紧随其后的补充（账号 / 原因 / 指引），**永远非空**：
 *   状态卡因此高度恒定，通知 text 也永不为空。
 */
data class StatusPresentation(
    val label: String,
    val detail: String,
    val icon: ImageVector,
)

object AuthStatus {

    /** 引擎 detail 里无信息量的词：不进副行（主词已覆盖） */
    private val DETAIL_NOISE = setOf("未启动", "已停止", "启动")

    /** 探测返回 204：网络本身可通，无需认证 */
    fun isIdleConnected(engineState: Int, detail: String): Boolean =
        engineState == Mobile.StateDetecting && detail.contains("无需认证")

    /** 账号脱敏（与引擎侧 maskUser 同规则：`ab***yz`，≤4 位原样）——卡片与通知共用 */
    fun maskUser(u: String): String {
        val r = u.toCharArray()
        if (r.size <= 4) return u
        return String(r, 0, 2) + "***" + String(r, r.size - 2, 2)
    }

    /** 当前认证账号（脱敏），供在线/认证中副行使用 */
    private fun accountDetail(fallback: String?): String? =
        (EngineHolder.currentAccount?.username ?: fallback)
            ?.let { "账号 ${maskUser(it)}" }

    fun presentation(state: UiState, serviceRunning: Boolean): StatusPresentation {
        // 未连接无线网络：优先于一切引擎状态（引擎此时必然已停止/待命）
        if (!state.wifiOnline) {
            return StatusPresentation("未连接无线网络", "连接 WiFi 后自动开始", Icons.Outlined.WifiOff)
        }
        val s = state.engineState
        return when {
            s == Mobile.StateOnline -> StatusPresentation(
                "已在线",
                accountDetail(state.onlineAccount) ?: "网络已认证",
                Icons.Outlined.CloudDone,
            )

            isIdleConnected(s, state.detail) -> StatusPresentation(
                "已联网 · 待机", "当前网络无需认证", Icons.Outlined.Wifi)

            s == Mobile.StateAuthorizing -> StatusPresentation(
                "认证中…",
                accountDetail(state.onlineAccount) ?: "正在提交认证",
                Icons.Outlined.Key,
            )

            s == Mobile.StateDetecting -> {
                // detail 里有价值的过渡说明（断开重连等）优先，无则用中性说明
                val extra = state.detail.takeIf {
                    it.isNotBlank() && it !in DETAIL_NOISE && !it.contains("无需认证")
                }
                StatusPresentation(
                    "探测网络中", extra ?: "正在检测网络连通性",
                    Icons.Outlined.WifiFind,
                )
            }

            s == Mobile.StateLoggedOut -> StatusPresentation(
                "已登出", "继续监控网络中", Icons.Outlined.Logout)

            s == Mobile.StateError -> StatusPresentation(
                "异常重试中", state.detail.ifBlank { "出现异常" }, Icons.Outlined.CloudOff)

            // 引擎 Idle：服务在跑但引擎待命（黑名单未触发等）与真正的未启动分开
            serviceRunning -> StatusPresentation(
                "待命中", "当前网络未触发认证", Icons.Outlined.PauseCircle)

            else -> StatusPresentation("未启动", "服务未运行", Icons.Outlined.PowerSettingsNew)
        }
    }
}
