# Better Esurfing for Android — 一键构建
#
# 依赖：
#   Go >= 1.22, JDK 17, Android SDK(platforms;android-26/35, build-tools;35.0.0), NDK r27,
#   gomobile+gobind (`go install golang.org/x/mobile/cmd/gomobile@latest && go install golang.org/x/mobile/cmd/gobind@latest`)
#
# Gradle 统一走仓库内 Wrapper（app/android/gradlew，8.11.1，发行包走腾讯镜像），无需本机装 Gradle。
# JDK/SDK 默认指向本机工具链 D:/Coding/_be-toolchain；对应环境变量已设置时优先生效
# （make 会导入环境变量，故 JAVA_HOME / ANDROID_HOME / ANDROID_NDK_HOME 可直接覆盖）。

GO                ?= go
JAVA_HOME         ?= D:/Coding/_be-toolchain/jdk-17.0.20+8
ANDROID_HOME      ?= D:/Coding/_be-toolchain/sdk
ANDROID_NDK_HOME  ?= $(ANDROID_HOME)/ndk/27.2.12479018

export JAVA_HOME
export ANDROID_HOME
export ANDROID_NDK_HOME
export PATH=$(JAVA_HOME)/bin:$(shell echo $$PATH)
export GOPROXY=https://goproxy.cn,direct
export _JAVA_OPTIONS=-Dfile.encoding=UTF-8

.PHONY: test aar apk debug clean

# M1：Go 引擎全量测试
test:
	cd app/core && $(GO) test ./...

# gomobile bind → AAR（Kotlin 工程引用 app/android/app/libs/betteresurfing-core.aar）
# 注：AAR 已入库，不重编 Go 引擎时可直接 `cd app/android && ./gradlew assembleRelease`
aar:
	cd app/core && gomobile bind -target=android -androidapi=26 -javapkg=dev.echoping.be \
		-o ../android/app/libs/betteresurfing-core.aar ./mobile

# 签名 release APK（分架构 arm64-v8a / armeabi-v7a）→ dist/
# 正式签名需 app/android/keystore.properties（模板 keystore.properties.example），
# 缺失时回退 debug 签名（仅开发用）
apk: aar
	cd app/android && ./gradlew assembleRelease --no-daemon
	mkdir -p dist
	cp app/android/app/build/outputs/apk/release/app-*-release.apk dist/

debug: aar
	cd app/android && ./gradlew assembleDebug --no-daemon

clean:
	cd app/core && $(GO) clean ./...
	cd app/android && ./gradlew clean --no-daemon || true
