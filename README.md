# Moonlight Android

[![AppVeyor Build Status](https://ci.appveyor.com/api/projects/status/232a8tadrrn8jv0k/branch/master?svg=true)](https://ci.appveyor.com/project/cgutman/moonlight-android/branch/master)
[![Translation Status](https://hosted.weblate.org/widgets/moonlight/-/moonlight-android/svg-badge.svg)](https://hosted.weblate.org/projects/moonlight/moonlight-android/)

[Moonlight for Android](https://moonlight-stream.org) is an open source client for NVIDIA GameStream and [Sunshine](https://github.com/LizardByte/Sunshine).

## harkerhand 二次开发版本

本仓库在 Moonlight Android 基础上增加了面向音乐播放场景的纯音频串流模式。
该模式可以从应用列表中的应用菜单启动，使用 Android 前台媒体服务持有串流连接，
支持退到后台、锁屏和通过系统媒体通知停止串流。纯音频页面不会创建视频解码器，
也不会初始化触摸、键盘或手柄输入。

纯音频模式仍使用现有 GameStream/Sunshine 连接流程。由于原协议需要视频流保持
会话兼容，客户端会请求一条低规格兼容视频流并直接丢弃视频帧；这不是服务端真正
关闭视频捕获和编码的 audio-only 协议。详细的连接时序、参数和限制见
[纯音频协议说明](docs/audio-only-protocol.md)。

纯音频兼容视频的分辨率、帧率和码率可以在“设置 → 音频设置”中独立配置。默认值
为 `320x180 / 10 FPS / 100 Kbps`，极低分辨率和帧率属于实验选项，可能不被某些
主机编码器支持。性能统计开关打开后，纯音频页面会显示 RTT、音频队列、AudioTrack
写入耗时以及兼容视频接收情况。

这是一个二次开发版本，应用 ID 为 `cn.harkerhand.moonlight`（root flavor 为
`cn.harkerhand.moonlight.root`），与官方 Moonlight Android 包并列安装。

Moonlight for Android will allow you to stream your full collection of games from your Windows PC to your Android device,
whether in your own home or over the internet.

Moonlight also has a [PC client](https://github.com/moonlight-stream/moonlight-qt) and [iOS/tvOS client](https://github.com/moonlight-stream/moonlight-ios).

You can follow development on our [Discord server](https://moonlight-stream.org/discord) and help translate Moonlight into your language on [Weblate](https://hosted.weblate.org/projects/moonlight/moonlight-android/).

## Downloads
* [Google Play Store](https://play.google.com/store/apps/details?id=com.limelight)
* [Amazon App Store](https://www.amazon.com/gp/product/B00JK4MFN2)
* [F-Droid](https://f-droid.org/packages/com.limelight)
* [APK](https://github.com/moonlight-stream/moonlight-android/releases)

## Building
* Install Android Studio and the Android NDK
* Run ‘git submodule update --init --recursive’ from within moonlight-android/
* In moonlight-android/, create a file called ‘local.properties’. Add an ‘ndk.dir=’ property to the local.properties file and set it equal to your NDK directory.
* Build the APK using Android Studio or gradle

The main debug variants are:

```sh
./gradlew assembleNonRootDebug
./gradlew assembleRootDebug
```

## Authors

* [Cameron Gutman](https://github.com/cgutman)  
* [Diego Waxemberg](https://github.com/dwaxemberg)  
* [Aaron Neyer](https://github.com/Aaronneyer)  
* [Andrew Hennessy](https://github.com/yetanothername)

Moonlight is the work of students at [Case Western](http://case.edu) and was
started as a project at [MHacks](http://mhacks.org).
