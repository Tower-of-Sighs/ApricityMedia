# Apricity-Media 附属模组说明

`Apricity-Media` 是专门为 `ApricityUI` 开发的多媒体扩展插件。它通过集成高性能的 **FFmpeg** 原生库，让你可以像在现代浏览器中一样，直接在 Minecraft UI 中嵌入和控制音视频。

## 核心特性

* **FFmpeg 驱动**：支持几乎所有主流音视频格式（MP4, MKV, WebM, MP3, OGG, WAV 等）。
* **HTML 标签化**：直接使用标准的 `<video>` 和 `<audio>` 标签进行开发。
* **浏览器标准 API**：支持 `play()`、`pause()`、`load()` 等标准方法，以及 `play`、`pause`、`timeupdate`、`ended` 等媒体事件。
* **属性查询**：支持 `currentTime`、`duration`、`paused`、`volume`、`muted` 等状态属性查询。

---

## 快速开始

```html
<video id="myVideo" src="demo.mp4" autoplay loop muted></video>
<audio id="myAudio" src="bgm.mp3" autoplay loop volume="0.8"></audio>

<script>
  var video = document.querySelector("#myVideo");
  video.play();  // 开始播放

  var audio = document.querySelector("#myAudio");
  audio.pause(); // 暂停音频
</script>
```

---

## 标签用法

### 1. 视频标签 `<video>`

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
| `src` | 视频资源路径（支持相对路径与远程 URL，如 `http://`、`https://`、HLS `.m3u8`） | 必填 |
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

`<video>` 和 `<audio>` 元素支持浏览器标准的媒体控制 API。以下所有方法均可在 JS 中直接调用。

### 播放控制

```javascript
var video = document.querySelector("#myVideo");

video.play();   // 开始播放（自动设置 autoplay=true, paused=false）
video.pause();  // 暂停播放（设置 paused=true）
video.load();   // 重新加载当前 src（重置播放位置与解码器）
```

### 状态属性查询

```javascript
var cur  = video.getCurrentTime();  // 当前播放位置（秒），未知时为 0
var dur  = video.getDuration();     // 媒体总时长（秒），未知时为 -1
var p    = video.isPaused();        // 是否暂停中
var e    = video.isEnded();         // 是否已播放完毕
var rdy  = video.getReadyState();   // 就绪状态: 0=HAVE_NOTHING, 1=HAVE_METADATA, 2+=可播放
var net  = video.getNetworkState(); // 网络状态: 0=EMPTY, 1=IDLE, 2=LOADING, 3=NO_SOURCE
var src  = video.getSrc();          // 当前源 URL
var vol  = video.getVolume();       // 当前音量 (0.0–1.0)
var m    = video.isMuted();         // 是否静音
var lp   = video.isLoop();          // 是否循环
var ap   = video.isAutoplay();      // autoplay 是否开启
```

### 属性设置

```javascript
video.setVolume(0.5);      // 设置音量
video.setMuted(true);      // 静音
video.setMuted(false);     // 取消静音
video.setLoop(true);       // 开启循环播放
```

### 自定义 HTTP 请求头与网络参数

```javascript
// 设置 User-Agent
video.setUserAgent("Mozilla/5.0 ...");

// 设置自定义 HTTP 请求头（FFmpeg 格式：每行 "Key: value"）
video.setHeaders(
  "accept: */*\r\n" +
  "referer: https://example.com/\r\n" +
  "origin: https://example.com\r\n"
);

// 设置任意 FFmpeg 网络选项
video.setNetworkOption("fflags", "nobuffer");
video.setNetworkOption("flags", "low_delay");
video.setNetworkOption("reconnect", "0");
video.setNetworkOption("http_persistent", "0");

// 查看当前设置
var opts = video.getNetworkOptions();
console.log(JSON.stringify(opts));

// 清除所有自定义网络选项
video.clearNetworkOptions();
```

---

## 媒体事件

媒体元素会触发浏览器标准的媒体事件，可通过 `addEventListener` 监听：

| 事件名 | 触发时机 |
| --- | --- |
| `loadstart` | 开始加载媒体资源 |
| `loadedmetadata` | 元数据就绪（duration 已知） |
| `durationchange` | duration 属性发生变化 |
| `canplay` | 有足够数据可以开始播放 |
| `play` | 从暂停变为播放 |
| `pause` | 从播放变为暂停 |
| `timeupdate` | 播放过程中周期性触发（约每 250ms） |
| `ended` | 播放到媒体末尾（非循环模式下） |
| `volumechange` | 音量或静音状态发生变化 |
| `error` | 解码或播放出错 |

```javascript
var video = document.querySelector("#myVideo");

video.addEventListener("play", function() {
  console.log("视频开始播放");
});

video.addEventListener("timeupdate", function() {
  var cur = video.getCurrentTime();
  var dur = video.getDuration();
  console.log("播放进度: " + cur.toFixed(1) + " / " + dur.toFixed(1));
});

video.addEventListener("ended", function() {
  console.log("视频播放完毕");
});

video.addEventListener("volumechange", function() {
  console.log("音量变化: " + video.getVolume() + ", 静音: " + video.isMuted());
});
```

---

## HLS 自适应码率

### 元素级 HLS 策略

通过 `hls-policy` 属性控制 HLS 变体选择：

```html
<video src="playlist.m3u8" hls-policy="highest"></video>
<audio src="playlist.m3u8" hls-policy="lowest"></audio>
```

| 策略值 | 说明 |
| --- | --- |
| `auto` | 视频默认选最高码率，音频默认选最低码率 |
| `highest` / `highest_bandwidth` | 选择最高码率变体 |
| `lowest` / `lowest_bandwidth` | 选择最低码率变体 |
| `highest_resolution` | 选择最高分辨率变体 |
| `lowest_resolution` | 选择最低分辨率变体 |
| `off` / `none` / `disabled` | 不做 HLS 变体选择，直接使用 URL |

### 全局 HLS 工具方法

通过全局 `ApricityMediaHls` 对象或任意媒体元素上的静态方法调用：

```javascript
// 全局绑定（KubeJS 环境中可用）
var bestUrl = ApricityMediaHls.highest("https://example.com/playlist.m3u8");
var lowUrl  = ApricityMediaHls.lowest("https://example.com/playlist.m3u8");
var selUrl  = ApricityMediaHls.select("https://example.com/playlist.m3u8", "highest_resolution");

// 也可通过元素调用（结果相同）
var url = document.querySelector("#myVideo").hlsHighest(playlistUrl, 8000);
```

方法签名：

| 方法 | 说明 |
| --- | --- |
| `hlsHighest(url [, timeoutMs])` | 选择最高码率变体 |
| `hlsLowest(url [, timeoutMs])` | 选择最低码率变体 |
| `hlsSelect(url, policy [, timeoutMs])` | 按策略名选择变体 |
| `hlsResolve(url [, timeoutMs])` | 解析 HLS URL（同 highest） |

---

## 完整示例

```html
<video id="player" src="demo.mp4" loop muted decode-width="1280" max-fps="30"></video>

<script>
  var video = document.querySelector("#player");

  // 监听元数据加载完成
  video.addEventListener("loadedmetadata", function() {
    console.log("时长: " + video.getDuration().toFixed(1) + " 秒");
    video.play();
  });

  // 显示播放进度
  video.addEventListener("timeupdate", function() {
    var pct = video.getDuration() > 0
      ? (video.getCurrentTime() / video.getDuration() * 100).toFixed(0)
      : 0;
    console.log("进度: " + pct + "%");
  });

  // 循环结束时自动重播（loop 属性已处理此情况）
  video.addEventListener("ended", function() {
    if (video.isLoop()) {
      video.play();
    }
  });

  // 音量控制
  video.setVolume(0.5);

  // 加载 HLS 网络流
  video.setUserAgent("Mozilla/5.0 ...");
  video.setAttribute("src", "https://example.com/stream.m3u8");
  video.setAttribute("network-timeout-ms", "8000");
  video.setAttribute("network-buffer-kb", "2048");
  video.setAttribute("hls-policy", "auto");
  video.load();
  video.play();
</script>
```

---

## 注意事项

1. **原生库依赖**：由于集成了 FFmpeg，该模组体积较大。
2. **内存占用**：高分辨率视频解码较占内存，建议根据 UI 实际显示大小设置 `decode-width`。
3. **HLS 日志现象**：`Opening '...ts' for reading` 是 HLS 分片拉流与码率切换的正常行为，不是错误。
4. **HLS 卡顿优化**：优先用单码率 m3u8 或降低分辨率档位；并适当提高 `network-buffer-kb`，同时限制 `max-fps`。
5. **currentTime 精度**：`currentTime` 基于解码帧的 PTS 时间戳估算，对于直播流或变帧率媒体，精度会有所下降。
6. **duration 可用性**：对于某些直播流或特殊格式，`duration` 可能返回 `-1`（表示未知），此时应在 JS 中做 NaN 检查。
