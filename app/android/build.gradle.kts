plugins {
    // navigation3 1.0.0 / activity 1.12.0 的 aar-metadata 要求 minAgp=8.9.1
    id("com.android.application") version "8.9.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
    // Navigation3 rememberNavBackStack 进程死亡恢复需要 @Serializable 路由键
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.21" apply false
}
