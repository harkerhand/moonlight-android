# Moonlight Android 架构说明

## 项目概览

Moonlight Android 是 NVIDIA GameStream 和 Sunshine 的开源 Android 客户端。
它用于把 Windows PC 上的游戏串流到 Android 手机、平板、电视设备以及相关
Android 环境。

这个仓库是单模块 Gradle Android 应用：

- `settings.gradle` 只包含 `:app`。
- `app/build.gradle` 定义 Android application，并包含 `root` 和 `nonRoot`
  两个 product flavor。
- Java 源码位于 `app/src/main/java/com/limelight`。
- Native C 代码通过 `ndk-build` 从 `app/src/main/jni/Android.mk` 构建。
- Android 资源、偏好设置、Manifest、网络和备份 XML 位于
  `app/src/main/res` 和 `app/src/main/AndroidManifest.xml`。

主要运行链路：

1. `PcView` 是启动 Activity。它展示已发现或已保存的 PC，处理配对、
   wake-on-LAN、快捷方式，以及跳转到设置或应用列表。
2. `ComputerManagerService` 负责主机轮询、主机持久化、mDNS 发现集成，
   并把状态更新回调给 UI listener。
3. `DiscoveryService` 封装 mDNS 发现。Android 14 之前使用
   `JmDNSDiscoveryAgent`，Android 14 及之后使用 `NsdManagerDiscoveryAgent`。
4. `AppView` 展示选中主机的应用列表、缓存封面、运行状态，以及启动/退出
   操作。
5. `Game` 负责普通全屏串流会话。它连接 Android 输入、虚拟手柄、音视频
   renderer、Wi-Fi lock、PiP 行为和 `NvConnection` 生命周期。
6. `AudioOnlyPlayerActivity` 是仅音频播放器 UI；实际连接由前台
   `AudioOnlyStreamService` 持有，因此页面退到后台、锁屏或从最近任务划走时
   不会因 Activity 生命周期而停止。该模式不初始化输入或视频解码器，使用
   `NoOpVideoRenderer` 丢弃兼容视频流；其分辨率、帧率和码率可在“音频设置”
   中独立配置，默认值为兼容优先的 320x180、10 FPS、100 Kbps；更低参数由
   用户手动选择。
   Service 在整个播放期间以 200 ms 粒度持续采集 RTT、音频队列、AudioTrack
   和兼容视频指标，并保存最近 60 秒的 300 个样本；“显示性能统计”偏好只控制
   播放器页面是否读取并绘制历史，不影响后台 trace 采集。
   Service 使用 `START_REDELIVER_INTENT` 并在应用私有 SharedPreferences 中保存
   当前会话参数；进程被系统回收后可恢复。非正常断线会保持前台通知并按退避
   间隔自动重连，只有显式停止或主机正常结束会话才清除恢复状态。
7. `NvConnection` 与主机协商串流参数，校验配对状态，启动/停止应用，并进入
   native 串流桥接层。
8. `MoonBridge` 加载 `libmoonlight-core.so`，并暴露 Java 到 native 的桥接
   调用，用于串流、输入、音频、视频和连接状态。

重要源码边界：

- `com.limelight.binding`：Android 平台相关实现，包括音频、视频、加密、
  输入捕获、USB driver、evdev、触摸和虚拟手柄。
- `com.limelight.computers`：已保存主机数据库、身份标识、旧数据库迁移、
  轮询和主机管理服务。
- `com.limelight.discovery` 和 `com.limelight.nvstream.mdns`：mDNS 服务发现
  实现。
- `com.limelight.nvstream`：串流配置、连接生命周期、输入包模型、JNI 桥、
  HTTP 协议、配对、wake-on-LAN 和 AV renderer 接口。
- `com.limelight.grid` 和 `com.limelight.grid.assets`：PC/应用网格 adapter，
  以及缓存或网络加载的封面资源。
- `com.limelight.preferences`：旧版 Android preference 页面，以及串流和 UI
  使用的类型化偏好读取逻辑。
- `app/src/main/jni/moonlight-core`：JNI glue 加
  `moonlight-common-c` submodule。
- `app/src/main/jni/evdev_reader`：root flavor 专用 native evdev reader
  可执行文件。

## 构建与命令

仓库说明中的前置条件：

- 安装 Android Studio 和 Android NDK。
- 执行 `git submodule update --init --recursive`，确保
  `app/src/main/jni/moonlight-core/moonlight-common-c` 存在。
- 在仓库根目录创建 `local.properties`，写入 `ndk.dir=<path-to-ndk>`。
- `app/build.gradle` 固定 `ndkVersion "29.0.14206865"`。
- Gradle wrapper 使用 Gradle `9.7.1`。
- Java source/target compatibility 是 Java 17。

常用本地命令：

```sh
./gradlew build
./gradlew connectedCheck
./gradlew lint
./gradlew assembleNonRootDebug
./gradlew assembleRootDebug
./gradlew assembleNonRootRelease
./gradlew assembleRootRelease
```

Windows CI 使用：

```bat
gradlew.bat build connectedCheck
```

构建变体：

- `nonRoot` flavor 使用 application ID `cn.harkerhand.moonlight`。
- `root` flavor 使用 application ID `cn.harkerhand.moonlight.root`，设置
  `maxSdk 25`，并向 `ndk-build` 传递 `PRODUCT_FLAVOR=root`。
- `debug` build 使用 debug 应用名称。
- `release` build 启用 minification，并使用
  `app/proguard-rules.pro`。

Native 构建说明：

- `app/src/main/jni/Android.mk` 包含所有子目录 makefile。
- `moonlight-core/Android.mk` 从 `moonlight-common-c`、JNI glue、callbacks
  和 mini SDL 支持代码构建 `libmoonlight-core.so`。
- 预构建静态库 `libopus.a` 和 `libcrypto.a` 会按 ABI 从
  `moonlight-core/opus` 和 `moonlight-core/openssl` 选择。
- `evdev_reader` 只在 `root` flavor 构建。
- `Application.mk` 设置 `APP_PLATFORM := android-21`，并启用 16 KB page
  支持。

发布相关说明：

- 当前 AppVeyor 配置中 `deploy: off`。
- README 中列出的下载渠道包括 Google Play、Amazon App Store、F-Droid 和
  GitHub release APK。
- release build 文件中的注释明确要求：如果发布非官方 APK，需要更改
  application ID。

## 代码风格

代码库由 Java 和 C/JNI 组成。当前检入源码中没有 Kotlin。

已观察到的 Java 约定：

- package 使用 `com.limelight` 下的小写路径。
- class 和 interface 使用 `PascalCase`。
- method 和 field 使用 `camelCase`。
- 常量使用 `UPPER_SNAKE_CASE`。
- 缩进为 4 个空格。
- 大括号与声明或控制语句放在同一行。
- 代码使用明确的 Android framework 模式：`Activity`、`Service`、`Binder`、
  `ServiceConnection`、匿名内部类和手动管理的后台 `Thread`。
- 项目代码通常通过 `LimeLog` 记录日志。
- 面向用户的字符串应放在 Android resources 中；仓库存在大量本地化
  `values-*` 目录，lint 只禁用了 `MissingTranslation`。

从当前代码结构得出的开发边界：

- Android 平台相关实现放在 `binding`。
- 协议和串流协商逻辑放在 `nvstream`。
- 主机持久化和轮询逻辑放在 `computers`。
- 修改用户偏好时，同步维护 `res/xml/preferences.xml` 和
  `PreferenceConfiguration`。
- 修改 native bridge 时保留 JNI 方法名和 `MoonBridge` 常量；native
  callbacks 依赖这些契约。
- root-only 行为应保持在 product flavor 边界内。root flavor 有独立
  manifest overlay 和 native `PRODUCT_FLAVOR=root` 处理。

ProGuard/R8 配置：

- release minification 已启用。
- `app/proguard-rules.pro` 使用 `-dontobfuscate` 禁止混淆。
- 保留 `com.limelight.nvstream.jni.*` 下的 JNI bridge 类。
- 保留 `com.limelight.binding.input.evdev.*` 下的 evdev 输入类。
- BouncyCastle 和 Okio 的 keep/warning 规则已经存在。

## 测试

仓库中没有检入 `app/src/test` 或 `app/src/androidTest` 源码目录。
`app/build.gradle` 中也没有声明项目专用的 JUnit 或 Espresso 依赖。

仓库 CI 命令是：

```sh
./gradlew build connectedCheck
```

本地变更常用验证命令：

```sh
./gradlew build
./gradlew connectedCheck
./gradlew lint
```

当变更涉及 flavor 专用代码或 native 构建参数时，可以做变体构建检查：

```sh
./gradlew assembleNonRootDebug
./gradlew assembleRootDebug
```

串流、配对、发现、手柄、USB 或 root-only 相关变更通常需要手动测试或设备
测试。`connectedCheck` 需要已连接的 Android 设备或模拟器。

仅音频后台模式还应在真机验证：连接后按 Home、锁屏、从最近任务划掉播放器，
确认通知和音频继续；再通过通知或播放器的“停止串流”确认连接、MediaSession、
Wi-Fi lock 和 partial wake lock 都被释放。

## 安全

这个仓库中与安全相关的实现细节：

- Manifest 请求网络、Wi-Fi 状态、wake lock、前台媒体服务、通知、震动、
  键盘捕获、TV EPG 和 multicast 相关权限。
- `network_security_config.xml` 全局允许 cleartext traffic，并信任系统 CA。
- `NvHTTP` 使用 OkHttp、`Proxy.NO_PROXY`、显式 timeout、自定义
  `X509TrustManager`，以及允许已 pin 主机证书的 hostname verifier。
- `PairingManager` 使用客户端证书/私钥、PIN 派生 AES 材料、服务端证书校验
  和签名 challenge 完成 GameStream 配对流程。
- `AndroidCryptoProvider` 在 app files 目录中保存客户端证书和私钥：
  `client.crt`、`client.key`。
- `ComputerDatabaseManager` 在私有 SQLite 数据库 `computers4.db` 中持久化
  已配对主机详情和服务端证书。
- `IdentityManager` 在 app files 目录中保存客户端唯一 ID：`uniqueid`。
- 备份规则排除了所有 shared preferences 的云备份和设备迁移，因为偏好设置
  经常包含设备相关数据。
- Manifest 中启用了 `gwpAsanMode="always"`。

安全敏感变更注意事项：

- 不要在没有同时追踪 `NvHTTP` 和 `PairingManager` 的情况下削弱证书 pinning、
  配对校验或 challenge-response 校验。
- 记录主机响应、配对状态、证书、密钥或 ID 时要谨慎。debug build 已经通过
  `BuildConfig.DEBUG` 让 `NvHTTP` 进入 verbose 模式。
- 将 `client.key`、`client.crt`、`uniqueid`、主机地址和持久化的服务端证书
  视为私有 app 数据。
- `AudioOnlySession` 私有 SharedPreferences 保存后台恢复所需的主机、应用、
  unique ID 和服务端证书；显式停止时必须清除，且继续保持在备份排除范围内。
- root-only 输入捕获和 evdev-reader 行为应保持在 `root` flavor 后面。
- 如果新增 SharedPreferences 或持久化设备/主机相关数据，需要复核备份规则。

## 配置

构建配置：

- 根目录 `build.gradle` 配置了 `mavenCentral()`、`google()` 和 JitPack。
- Android Gradle Plugin 版本是 `9.4.0`。
- `gradle.properties` 设置 `org.gradle.jvmargs=-Xmx3072m`。
- `compileSdk` 是 `37`，`targetSdk` 是 `36`，`minSdk` 是 `21`。
- `compileOptions.encoding` 是 `UTF-8`。
- core library desugaring 已启用，依赖为
  `com.android.tools:desugar_jdk_libs:2.1.5`。

运行时配置：

- 默认应用设置来自 `app/src/main/res/xml/preferences.xml`。
- `PreferenceConfiguration` 读取并规范化 preference 值，包括旧版
  resolution/bitrate 设置。
- 应用名称通过 build type 和 flavor 的 `resValue` 生成。
- `app/build.gradle` 禁用了 language 和 density bundle splits。
- `locales_config`、`backup_rules`、`backup_rules_s`、`game_mode_config` 和
  `network_security_config` 都从 Manifest 声明。

Flavor 配置：

- `app/src/root/AndroidManifest.xml` 设置 root 应用名称，并设置
  `android:extractNativeLibs="true"`，以便 rooted flavor 调用 native
  evdev reader binary。
- `app/src/nonRoot/AndroidManifest.xml` 设置普通应用名称。
- 只有 `root` flavor 的 `BuildConfig.ROOT_BUILD` 是 `true`。

外部依赖：

- `moonlight-common-c` 是 Git submodule，路径为
  `app/src/main/jni/moonlight-core/moonlight-common-c`。
- Java 依赖包括 BouncyCastle、JCodec、OkHttp、JmDNS 和
  `ShieldControllerExtensions`。
- Native 预构建依赖包括当前仓库中各 ABI 目录下的 OpenSSL `libcrypto.a` 和
  Opus `libopus.a`。

仓库规则：

- 创建本文档时，在标准位置未发现已有 `AGENTS.md`、`AGENT.md`、Cursor
  rules、Copilot instructions 或 Trae rules。
