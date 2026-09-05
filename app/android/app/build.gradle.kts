import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "dev.echoping.betteresurfing"
    // material3 1.5.0-alpha18 / compose 1.9.4 minCompileSdk=35、minAgp=8.6；
    // navigation3 1.0.0 minCompileSdk=36 → compileSdk 36
    // （alpha18 的 35 是下限，36 兼容；gradle.properties 已开 suppressUnsupportedCompileSdk=36）
    compileSdk = 36
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "dev.echoping.betteresurfing"
        minSdk = 26
        targetSdk = 35
        versionCode = 14
        versionName = "0.7.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            // 签名信息注入：环境变量（CI 用 GitHub Secrets）> keystore.properties（本地，
            // 已 gitignore，模板见 keystore.properties.example）。均未提供时 storeFile 保持
            // 为空，release 构建回退 debug 签名，保证克隆即可构建。
            val envFile = System.getenv("KEYSTORE_FILE")
            val propsFile = rootProject.file("keystore.properties")
            if (!envFile.isNullOrEmpty()) {
                storeFile = file(envFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            } else if (propsFile.exists()) {
                val props = Properties().apply {
                    propsFile.inputStream().use { load(it) }
                }
                storeFile = file(props.getProperty("KEYSTORE_FILE"))
                storePassword = props.getProperty("KEYSTORE_PASSWORD")
                keyAlias = props.getProperty("KEY_ALIAS")
                keyPassword = props.getProperty("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val releaseSigning = signingConfigs.getByName("release")
            signingConfig = if (releaseSigning.storeFile != null) {
                releaseSigning
            } else {
                logger.warn("未检测到签名配置（环境变量 KEYSTORE_FILE 或 app/android/keystore.properties），release 使用 debug 签名")
                signingConfigs.getByName("debug")
            }
        }
    }

    splits {
        abi {
            // 分架构出包：arm64-v8a 与 armeabi-v7a 各一个 APK（Go so 按架构独立分发）
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            // 需要双架构合一包时改为 true
            isUniversalApk = false
        }
    }
    lint {
        // AGP 8.7 内置 lint 的 lifecycle 探测器与 Kotlin 2.2 UAST 不兼容（IncompatibleClassChangeError），
        // 禁用误报检查；真实 lint 在 CI 用更高版本 AGP 跑
        disable += "NullSafeMutableLiveData"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        aidl = true
        // 关于页展示 VERSION_NAME / VERSION_CODE
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Go 协议引擎（gomobile bind 产物）
    implementation(files("libs/betteresurfing-core.aar"))

    val composeBom = platform("androidx.compose:compose-bom:2025.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    // MD3 Expressive：BOM 管理 compose 基础库（ui/foundation 1.9.4），
    // material3 显式升到 1.5.0-alpha18 拿全套 Expressive 组件
    // （MaterialExpressiveTheme、波形进度指示器等）；1.5.0-alpha20 起需要 SDK 37。
    implementation("androidx.compose.material3:material3:1.5.0-alpha18")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")

    implementation("androidx.core:core-ktx:1.15.0")
    // navigation3-ui 1.0.0 要求 activity 1.12.0（ComponentActivity 自此实现
    // NavigationEventDispatcherOwner，预测性返回的 navigationevent 管线依赖它）
    implementation("androidx.activity:activity-compose:1.12.0")
    // Navigation3：NavDisplay 全默认（前进/pop 700ms fade、预测返回 spring+scaleOut(0.7)，
    // 与 SukiSU Ultra 同款；「预测性返回手势」开关走 BeApplication 反射系统级 opt-in）
    implementation("androidx.navigation3:navigation3-ui:1.0.0")
    implementation("androidx.navigation3:navigation3-runtime:1.0.0")
    // 反射 ApplicationInfo.setEnableOnBackInvokedCallback（API 34+ 隐藏 API）
    // 运行时切换预测性返回总闸所需（SukiSU Ultra 同版本）
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Shizuku（免 root 的特权 shell）
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // 主题取色（HCT）纯 JVM 单测
    testImplementation("junit:junit:4.13.2")
}
