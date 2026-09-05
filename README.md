# Better Esurfing for Android

> [!WARNING]
> **维护状态**：这是本人暑假期间用约一周时间完成的项目。由于本学期校园网运营商调整，无法进行实际测试，本次发布即为最终版本（新认证算法目前仅理论上支持）。欢迎 fork 后自行修改；请勿提交 Issue 和 PR。仓库将长期保留，以此留作纪念。

## 简介

第三方广东电信天翼校园网（CCTP/ZSM 验证）认证客户端 —— **Go 协议引擎 + Android 原生壳（Kotlin · Compose · Material Design 3）**。

> 目标：替代难用的官方"广东校园"App。支持自动认证、心跳保活、断线重连、
> WiFi 白名单/黑名单触发、多账号快捷切换、开机自启，以及 Standard / Shizuku / Root 三档运行模式。

## 下载安装

从 [GitHub Releases](https://github.com/EchoPing07/Better-Esurfing-For-Android/releases/latest) 下载对应架构的 APK：

| 包名 | 适用设备 |
| --- | --- |
| `BetterEsurfing-v*-arm64-v8a.apk` | 绝大多数智能手机（64 位），**一般选这个** |
| `BetterEsurfing-v*-armeabi-v7a.apk` | 老 32 位设备 |

## 快速构建

```bash
# 只构建 Android 壳（无需 Go 环境，Go 引擎 AAR 已入库）：
#   产物在 app/android/app/build/outputs/apk/release/（分架构）
cd app/android && ./gradlew assembleRelease

# 全量构建（本机已配好工具链时，路径见 Makefile）：
make test   # Go 引擎测试
make apk    # gomobile bind → 分架构签名 release APK → dist/
```

- 需要 JDK 17 与 Android SDK（`local.properties` 的 `sdk.dir` 指向 SDK）；
  Gradle 版本由仓库内 Wrapper 锁定（8.11.1，发行包走腾讯镜像，国内可直接 `./gradlew`）。
- **签名**：release 默认回退 debug 签名（仅供开发调试）。要出正式签名包，复制
  `app/android/keystore.properties.example` 为同目录 `keystore.properties` 并填入
  自己的 keystore 信息（该文件已被 gitignore；CI 走同名环境变量注入）。

构建依赖（Go / JDK 17 / Android SDK / NDK / gomobile）详见 Makefile 头部注释。

## 算法来源与验证

CCTP 协议在 ZSM 握手时按会话下发加密算法 GUID（Algo-ID），且**服务器按认证 UA 家族分派算法池、
池内按会话随机轮换**，客户端必须实现整个算法池才能保证任意一次认证都能闭环。
本仓库内置全部三代 24 个算法，认证 UA 可自选并按 `2104 → 2089 → 2093` 自动回退
（与 [Esurfing-go-webui](https://github.com/DreamwareN/Esurfing-go-webui) 的落地形态同构）：

| 代际 | 通道（认证 UA） | 数量 | 算法 |
|---|---|---|---|
| 旧代 | 安卓（2089 / 2093） | 9 | 双层 AES-CBC/ECB、双层 3DES-CBC/ECB、SM4-CBC/ECB、XTEA-IV-CBC、ZUC 流密码 |
| 新代 | 新安卓（2104） | 9 | 映射组 6（结构归约 = 旧代标准基元换 GUID）+ 移植组 3（变体 TEA 三层 ECB/CBC、SNOW3G 变体流密码） |
| PC | PC 通道（1003） | 6 | 双层 AES-ECB/CBC、3DES-CBC（层序与安卓相反）、XTEA 三层、变体 TEA 三层 CBC |

### 来源与许可

| 代际          | 参考实现                                                                                                                                                                                                                                                                                                                                                                                                      | 许可                                             |
| ----------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------- |
| 旧代 9        | [xxmod/EsurfingGo](https://github.com/xxmod/EsurfingGo)（原始逆向谱系：Rsplwe/ESurfingDialer → EsurfingGo）                                                                                                                                                                                                                                                                                                        | MIT，见 [app/core/NOTICE.md](app/core/NOTICE.md) |
| 新代 9 + PC 6 | [Esurfing-go-webui](https://github.com/DreamwareN/Esurfing-go-webui)（作者本人的姊妹项目，三代 24 算法完整落地）；其上游参考：新代 9 + PC 6 ← [BadGhost520/ESurfingClient-CVersion](https://github.com/BadGhost520/ESurfingClient-CVersion)（算法源头；新代变体照其下游移植 [Ironjhin/EsurfingClient_Android](https://github.com/Ironjhin/EsurfingClient_Android) 内嵌的 C 引擎逐语句转录对照，[esp32_esurfing](https://github.com/MYHealer/esp32_esurfing) 同源交叉验证） | Apache-2.0                                     |

### 密钥说明

全部算法的密钥与 IV 均为**协议固定常量**（逆向自官方客户端内置映射表），并非用户密钥。
这些加密仅用于满足服务端协议校验，**不具备防窃听 / 防篡改的安全强度**，不要将其视为安全通道。

### 验证

- **KAT 对拍**：`app/core/cipher/testdata/kat/` 15 个真值文件（新代 9 + PC 6，
  真值由按 C 参考源独立转录的 oracle 生成），实现加解密双向逐字节对拍；
- **mock e2e**：`mockportal` 全链路闭环（握手 → 票据 → 登录 → 心跳）覆盖 24 个算法 ID，
  另含 UA 回退链行为断言，`go test ./...` 全绿；
- **回归红线**：旧 9 个 GUID 的注册项与实现一行未动，旧配置 / 旧会话完全向后兼容。

## 目录结构

```
Better-Esurfing-For-Android/
├── Makefile              # 一键构建：make test / aar / apk / debug / clean
├── .github/workflows/    # Release 自动构建（打 v* tag 即发版）
├── app/                  # 代码（入库）
│   ├── core/             # Go 协议引擎（cipher / engine / portal / mobile / mockportal），
│   │                     #   gomobile bind → app/android/app/libs/betteresurfing-core.aar
│   └── android/          # Android 工程（Kotlin + Compose MD3，Gradle root 在此层；
│                         #   gradlew 与 keystore.properties.example 在此）
├── dist/                 # 构建产物 APK（不入库）
├── official/             # 官方 APK + 反编译产物
└── references/           # 上游参考项目
```

> `docs/`（开发过程中的协议分析、设计规范与发版手册）、`official/`（仅保留说明重建方式的
> README）与 `references/` 的实际内容均被 .gitignore 排除、仅本地保留。

## 致谢与声明

仓库：<https://github.com/EchoPing07/Better-Esurfing-For-Android>

协议研究成果建立在以下开源项目的肩膀上：**Rsplwe/ESurfingDialer**（原始 Kotlin 实现）、
**xxmod/EsurfingGo**（旧代 9 算法移植来源）、**BadGhost520/ESurfingClient-CVersion**（新代 9 与
PC 通道 6 算法的共同源头）、**Ironjhin/EsurfingClient_Android**（该 C 引擎的下游 Android 移植版，
新代变体转录对照），以及作者本人的姊妹项目
**[Esurfing-go-webui](https://github.com/DreamwareN/Esurfing-go-webui)**（三代 24 算法与
认证 UA 回退链的完整参考实现）。算法谱系详见[算法来源与验证](#算法来源与验证)。

本项目仅用于对本人合法账号进行自动化认证，不支持也不计划支持绕过计费、设备数限制等违规操作；
请遵守所在学校与运营商的使用规定。
