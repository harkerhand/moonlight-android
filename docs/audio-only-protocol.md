# 纯音频串流协议说明

## 定位

本版本的纯音频模式是客户端兼容层，不是对 GameStream/Sunshine 协议的扩展。
它复用现有的配对、HTTP 启动、RTSP 协商、音频 RTP/Opus 和视频 RTP/H.264 流程，
因此不需要修改 Sunshine，也不需要修改 `moonlight-common-c` 的协议格式。

## 连接时序

```text
应用列表
  -> AudioOnlyPlayerActivity
  -> AudioOnlyStreamService（前台 mediaPlayback Service）
  -> NvConnection
  -> HTTP launch/resume
  -> RTSP ANNOUNCE/SETUP/PLAY
  -> 音频 Opus -> AndroidAudioRenderer -> AudioTrack
  -> 兼容视频 H.264 -> NoOpVideoRenderer -> 丢弃
```

Service 是唯一的会话 owner。播放器 Activity 只负责页面、通知入口和性能采样；
退到后台、锁屏或从最近任务划掉 Activity 不会停止 Service 持有的连接。

Service 以 Android `mediaPlayback` 前台服务运行，并使用 `START_REDELIVER_INTENT`。
当前会话的主机、应用和证书参数保存在应用私有 SharedPreferences 中（不会进入云
备份或设备迁移），供系统回收进程后恢复。异常断线时 Service 保持前台通知，并按
1、2、5、10、30 秒上限的退避间隔持续重连；用户显式停止或主机正常结束会话时
会清除恢复参数。

## 兼容视频

客户端在 RTSP/启动参数中仍会发送视频视口、帧率和码率，供 Sunshine 创建兼容的
视频会话。默认参数为：

```text
分辨率：320x180
帧率：10 FPS
码率：100 Kbps
编码：H.264
```

这些参数可以在 Android“设置 → 音频设置 → 纯音频视频...”中修改。可选的极低
参数包括 `160x90`、`32x18`、`16x16` 和实验性的 `2x2`，帧率可选 5 或 2 FPS。
它们是否能启动取决于 Sunshine 使用的 VideoToolbox、NVENC、QSV、AMF、VAAPI
或其他编码后端；协议层没有统一的最小分辨率保证。

收到的视频数据经过 common-c 的帧重组后进入 `NoOpVideoRenderer`，不会进入
Android `MediaCodec`，也不会提交到 `Surface`。因此：

- Sunshine 仍可能执行桌面捕获、缩放和视频编码；
- 客户端不会承担视频解码和渲染压力；
- 当前不是服务端零视频编码方案；
- 将来要完全消除服务端视频压力，需要 Sunshine/common-c 增加真正的音频-only
  会话能力，并处理现有协议对视频流、首帧和会话保活的假设。

Sunshine 可能根据自身配置和编码循环实际发送高于客户端目标的帧率。客户端的
性能页面显示的是实际收到的独立视频帧率，不是请求值。

## 音频协商

纯音频模式不直接设置 Opus bitrate。音频声道数来自现有音频配置，Sunshine 根据
声道数和音频质量档选择 Opus CBR。以当前 Sunshine 实现为例，普通质量的参考值为：

| 声道 | Opus 总码率 |
| --- | ---: |
| 立体声 | 96 Kbps |
| 5.1 | 256 Kbps |
| 7.1 | 450 Kbps |

实际网络占用还包括 RTP/UDP/IP、FEC、加密和其他协议开销。纯音频客户端使用
`USAGE_MEDIA`/`CONTENT_TYPE_MUSIC`，并通过 Android 音频焦点接入系统媒体播放。

## 性能统计

Service 在整个播放期间以 200 ms 粒度持续采集性能 trace，并在内存中保存最近
60 秒的 300 个样本。切到后台或锁屏不会停止采集；“显示性能统计”只控制播放器
页面是否读取和绘制这些历史。当前指标是：

- 网络 RTT 和 RTT 波动；
- native 音频解码队列积压；
- `AudioTrack.write()` 平均/峰值阻塞耗时；
- 兼容视频实际接收 FPS 和基于帧号缺口的丢帧率。

其中兼容视频丢帧率是 FEC/重组后没有交付的帧估计，不是音频 RTP 包丢失率。

## 与服务端真正 audio-only 的差异

| 能力 | 当前客户端兼容模式 | 真正服务端 audio-only |
| --- | --- | --- |
| 音频播放 | 支持 | 支持 |
| 客户端视频解码 | 不执行，直接丢帧 | 不执行 |
| Sunshine 视频捕获 | 仍可能执行 | 可关闭 |
| Sunshine 视频编码 | 仍可能执行低规格编码 | 可关闭 |
| 对现有 GameStream/Sunshine 兼容性 | 高 | 需要协议和服务端协同修改 |
