package dev.echoping.betteresurfing.ui

import androidx.navigation3.runtime.NavKey
import androidx.savedstate.serialization.SavedStateConfiguration
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/**
 * 导航路由键（Navigation3）。
 *
 * typed + @Serializable：`rememberNavBackStack` 以此在旋转 / 进程死亡后恢复返回栈
 * （落地 §2.1「rememberSaveable 返回栈」的原始约定，此前 remember-only 实现旋转即丢栈）。
 * 设置各二级页与顶层页同栈拍平，单一 NavDisplay 管全部转场与返回。
 */
sealed interface BeNavKey : NavKey {
    @Serializable
    data object Home : BeNavKey

    @Serializable
    data object Settings : BeNavKey

    @Serializable
    data object SettingsMode : BeNavKey

    @Serializable
    data object SettingsRules : BeNavKey

    @Serializable
    data object SettingsAdvanced : BeNavKey

    @Serializable
    data object SettingsKeepAlive : BeNavKey

    @Serializable
    data object SettingsTheme : BeNavKey

    @Serializable
    data object Logs : BeNavKey

    @Serializable
    data object About : BeNavKey
}

/** rememberNavBackStack 的多态序列化配置（Nav3 cookbook 同款显式注册，基类为 NavKey）。 */
val BeNavSavedStateConfig = SavedStateConfiguration {
    serializersModule = SerializersModule {
        polymorphic(NavKey::class) {
            subclass(BeNavKey.Home::class, BeNavKey.Home.serializer())
            subclass(BeNavKey.Settings::class, BeNavKey.Settings.serializer())
            subclass(BeNavKey.SettingsMode::class, BeNavKey.SettingsMode.serializer())
            subclass(BeNavKey.SettingsRules::class, BeNavKey.SettingsRules.serializer())
            subclass(BeNavKey.SettingsAdvanced::class, BeNavKey.SettingsAdvanced.serializer())
            subclass(BeNavKey.SettingsKeepAlive::class, BeNavKey.SettingsKeepAlive.serializer())
            subclass(BeNavKey.SettingsTheme::class, BeNavKey.SettingsTheme.serializer())
            subclass(BeNavKey.Logs::class, BeNavKey.Logs.serializer())
            subclass(BeNavKey.About::class, BeNavKey.About.serializer())
        }
    }
}
