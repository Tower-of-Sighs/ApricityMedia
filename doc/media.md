# Apricity-Media 附属模组说明

`Apricity-Media` 是专门为 `ApricityUI` 开发的多媒体扩展插件。它通过集成高性能的 **FFmpeg** 原生库，让你可以像在现代浏览器中一样，直接在 Minecraft UI 中嵌入和控制音视频。

## 核心特性

* **FFmpeg 驱动**：支持几乎所有主流音视频格式（MP4, MKV, WebM, MP3, OGG, WAV 等）。
* **HTML 标签化**：直接使用标准的 `<video>` 和 `<audio>` 标签进行开发。
* **动态控制**：支持通过 JavaScript 或 Java API 实时修改音量、循环、播放状态等属性。

---

## 标签用法

### 1. 视频标签 `<video>`

视频标签支持自动播放、循环播放、自定义解码分辨率与封面图。

```html
<video 
  id="myVideo" 
  src="assets/modid/videos/bg.mp4" 
  autoplay 
  loop 
  muted 
  decode-width="1280" 
  max-fps="60"
  paused="false"
></video>

```

| 属性 | 说明 | 默认值 |
| --- | --- | --- |
| `src` | 视频资源路径（支持相对路径与 `apricityui/...`） | 必填 |
| `poster` | 封面图路径，在首帧出现前显示，留空则不显示 | 空 |
| `autoplay` | 是否在未 `paused` 时自动播放 | `false` |
| `loop` | 是否循环播放 | `false` |
| `muted` | 是否静音（只影响音轨） | `false` |
| `volume` | 音量大小 (0.0–1.0) | `1.0` |
| `paused` | 播放状态控制（存在且不为 `false`/`0` 则视为暂停） | `true` |
| `preload` | 预加载策略：`none` / `metadata` / `auto`，`autoplay` 生效时会强制预加载 | `auto` |
| `decode-width` | 解码目标宽度，≤0 使用源视频宽度 | 不设置 |
| `decode-height` | 解码目标高度，≤0 使用源视频高度 | 不设置 |
| `max-fps` | 限制最高解码帧率（0 为不限制） | `0` |
| `drop-frames` | 性能不足时是否允许跳帧以同步时间轴 | `true` |
| `blur` | 是否对视频纹理启用模糊采样（配合 UI 做背景模糊） | `false` |
| `network-timeout-ms` | 网络 I/O 超时（毫秒，仅远程 URL 生效） | `15000` |
| `network-buffer-kb` | 网络缓冲大小（KB，仅远程 URL 生效） | `512` |
| `network-reconnect` | 网络抖动后是否自动重连（仅远程 URL 生效） | `true` |

### 2. 音频标签 `<audio>`

音频标签用于背景音乐或音效播放，支持高精度音量与循环控制。

```html
<audio 
  id="bgm" 
  src="assets/modid/audio/music.mp3" 
  volume="0.8" 
  loop="true"
></audio>

```

| 属性 | 说明 | 默认值 |
| --- | --- | --- |
| `src` | 音频资源路径 | 必填 |
| `autoplay` | 是否在未 `paused` 时自动播放 | `false` |
| `loop` | 是否循环播放 | `false` |
| `muted` | 是否静音 | `false` |
| `paused` | 播放状态控制（存在且不为 `false`/`0` 则视为暂停） | `true` |
| `volume` | 音量大小 (0.0–1.0) | `1.0` |
| `preload` | 预加载策略：`none` / `metadata` / `auto` | `auto` |
| `network-timeout-ms` | 网络 I/O 超时（毫秒，仅远程 URL 生效） | `15000` |
| `network-buffer-kb` | 网络缓冲大小（KB，仅远程 URL 生效） | `512` |
| `network-reconnect` | 网络抖动后是否自动重连（仅远程 URL 生效） | `true` |

### 3. 网络推荐参数

| 场景 | 推荐参数 |
| --- | --- |
| 局域网（NAS/本地流媒体） | `network-timeout-ms="4000"` `network-buffer-kb="256"` `network-reconnect="true"` |
| 公网（HLS/CDN） | `network-timeout-ms="8000~15000"` `network-buffer-kb="768~2048"` `network-reconnect="true"` |

推荐同时配合：

- 视频：`decode-width="960"` 或 `1280`，`max-fps="24~30"`，`drop-frames="true"`；
- 音频：`preload="metadata"`，首播速度更快。

---

## 脚本交互 (JavaScript)

你可以像操作普通元素一样，通过脚本动态控制媒体：

```javascript
const video = document.querySelector("#myVideo");

// 切换播放 / 暂停
video.setAttribute("paused", video.getAttribute("paused") === "true" ? "false" : "true");

// 动态调整音量
const bgm = document.querySelector("#bgm");
bgm.setAttribute("volume", "0.5");

// 简单判断当前是否在播放（未暂停且已设置 autoplay）
const isPlaying = video.getAttribute("autoplay") != null && video.getAttribute("paused") !== "true";

```

## 注意事项

1. **原生库依赖**：由于集成了 FFmpeg，该模组体积较大。
2. **内存占用**：高分辨率视频解码较占内存，建议根据 UI 实际显示大小设置 `decode-width`。
3. **HLS 日志现象**：`Opening '...ts' for reading` 是 HLS 分片拉流与码率切换的正常行为，不是错误。
4. **HLS 卡顿优化**：优先用单码率 m3u8 或降低分辨率档位；并适当提高 `network-buffer-kb`，同时限制 `max-fps`。
