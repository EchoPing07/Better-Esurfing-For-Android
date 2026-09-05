package dev.echoping.betteresurfing

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dev.echoping.betteresurfing.store.Prefs

/**
 * 桌面图标运行时切换：launcher 入口是两个 activity-alias（LauncherMonet / LauncherBrand，
 * 见 AndroidManifest），各挂一种图标；本工具按偏好把恰好一个别名置为启用。
 *
 * - 莫奈取色版（ic_launcher，31+ 背景为 system_accent1_600，低版本回退品牌灰）
 * - 品牌灰版（ic_launcher_brand，固定 #828282）
 *
 * 注意：Application 级图标（最近任务/系统设置里）静态绑定莫奈版资源，无法运行时切换；
 * 关闭取色后仅桌面图标变灰，属 alias 方案的固有限制。个别启动器缓存图标较顽固，
 * 切换后可能需要数秒甚至重启启动器才刷新。
 */
object LauncherIcon {
    private fun monetCn(ctx: Context) = ComponentName(ctx, "dev.echoping.betteresurfing.LauncherMonet")
    private fun brandCn(ctx: Context) = ComponentName(ctx, "dev.echoping.betteresurfing.LauncherBrand")

    /** 按偏好同步别名启用态（幂等；未变化不触发 PACKAGE_CHANGED）。 */
    fun apply(ctx: Context) {
        // API 31 以下 system_accent1_600 不存在，莫奈版与品牌灰视觉相同，固定走品牌灰
        val monet = Prefs.iconMonet && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val pm = ctx.packageManager
        setEnabled(pm, monetCn(ctx), monet)
        setEnabled(pm, brandCn(ctx), !monet)
    }

    private fun setEnabled(pm: PackageManager, cn: ComponentName, enable: Boolean) {
        val want = if (enable) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        if (pm.getComponentEnabledSetting(cn) == want) return
        pm.setComponentEnabledSetting(cn, want, PackageManager.DONT_KILL_APP)
    }
}
