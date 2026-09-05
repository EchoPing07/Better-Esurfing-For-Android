// 仓库源策略：
// - CI（GitHub Actions 等设了 CI 环境变量的环境）直连官方源——海外机器到
//   google()/mavenCentral() 快且全，而阿里云镜像偶发 5xx 会直接中止 Gradle
//   解析（Gradle 对 5xx 不做仓库间 fallthrough，镜像放进去只是风险）。
// - 本地（国内网络）阿里云镜像优先，官方源兜底。
// 注意：pluginManagement 块为隔离作用域，无法引用脚本顶层变量，故判断内联。
pluginManagement {
    repositories {
        if (System.getenv("CI") == null) {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/gradle-plugin")
            maven("https://maven.aliyun.com/repository/public")
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (System.getenv("CI") == null) {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "BetterEsurfing"
include(":app")
