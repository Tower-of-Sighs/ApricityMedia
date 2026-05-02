# Apricity-Media 附属模组说明

`Apricity-Media` 是 `ApricityUI` 的多媒体扩展模组，基于 FFmpeg 原生库，在 Minecraft UI 中嵌入和控制音视频。

## 核心特性

- **FFmpeg 驱动**：支持 MP4、MKV、WebM、MP3、OGG、WAV 等主流格式
- **HTML 标签**：使用 `<video>` 和 `<audio>` 标签
- **浏览器标准 API**：`play()` / `pause()` / `load()`，以及媒体事件
- **属性查询**：`currentTime`、`duration`、`volume`、`muted` 等
- **HLS 自适应码率**：元素上直接调用 `hlsHighest()` / `hlsLowest()` / `hlsSelect()`
- **B站直播流接入**：`ApricityMediaHls.getBilibiliPlayUrl(roomId)` 自动获取实时流地址

---

## 快速开始

```html
<video id="myVideo" src="demo.mp4" autoplay loop muted></video>
<audio id="myAudio" src="bgm.mp3" autoplay loop volume="0.8"></audio>

<script>
  var video = document.querySelector("#myVideo");
  video.play();

  var audio = document.querySelector("#myAudio");
  audio.pause();
</script>
```

---

## 标签属性

### `<video>`

| 属性 | 说明 | 默认值 |
| --- | --- | --- |
| `src` | 资源路径（相对路径 / 远程 URL / HLS `.m3u8`） | 必填 |
| `poster` | 封面图路径 | 空 |
| `autoplay` | 未 paused 时自动播放 | `false` |
| `loop` | 循环播放 | `false` |
| `muted` | 静音 | `false` |
| `volume` | 音量 0.0–1.0 | `1.0` |
| `paused` | 暂停状态（不为 `false`/`0` 即为暂停） | `true` |
| `preload` | `none` / `metadata` / `auto` | `auto` |
| `decode-width` | 解码目标宽度，≤0 使用源尺寸 | `0` |
| `decode-height` | 解码目标高度，≤0 使用源尺寸 | `0` |
| `max-fps` | 最高解码帧率（0=不限） | `0` |
| `drop-frames` | 性能不足时跳帧 | `true` |
| `blur` | 视频纹理模糊采样 | `false` |
| `hls-policy` | HLS 变体策略：`auto` / `highest` / `lowest` / `highest_resolution` / `lowest_resolution` / `off` | `auto` |
| `network-timeout-ms` | 网络超时（毫秒） | `15000` |
| `network-buffer-kb` | 网络缓冲（KB） | `512` |
| `network-reconnect` | 网络断线重连 | `true` |

### `<audio>`

| 属性 | 说明 | 默认值 |
| --- | --- | --- |
| `src` | 资源路径 | 必填 |
| `autoplay` | 未 paused 时自动播放 | `false` |
| `loop` | 循环播放 | `false` |
| `muted` | 静音 | `false` |
| `paused` | 暂停状态 | `true` |
| `volume` | 音量 0.0–1.0 | `1.0` |
| `preload` | `none` / `metadata` / `auto` | `auto` |
| `hls-policy` | HLS 变体策略 | `auto` |
| `network-timeout-ms` | 网络超时（毫秒） | `15000` |
| `network-buffer-kb` | 网络缓冲（KB） | `512` |
| `network-reconnect` | 网络断线重连 | `true` |

### 网络推荐参数

| 场景 | 推荐参数 |
| --- | --- |
| 局域网 | `network-timeout-ms="4000"` `network-buffer-kb="256"` |
| 公网 HLS/CDN | `network-timeout-ms="8000~15000"` `network-buffer-kb="768~2048"` |
| B站直播流 | `network-reconnect="true"`（通过 API 获取地址，无需手动设置 src） |

---

## JavaScript API

`<video>` 和 `<audio>` 元素提供以下方法（对 Audio 同样适用）。

### 播放控制

```javascript
video.play();   // 开始播放
video.pause();  // 暂停
video.load();   // 重新加载当前 src
```

### 属性查询

```javascript
video.getCurrentTime();   // 当前播放位置（秒），未知=0
video.getDuration();      // 总时长（秒），未知=-1
video.isPaused();         // 是否暂停
video.isEnded();          // 是否播完
video.getReadyState();    // 0=NOTHING 1=METADATA 2=CURRENT_DATA 3=FUTURE 4=ENOUGH
video.getNetworkState();  // 0=EMPTY 1=IDLE 2=LOADING 3=NO_SOURCE
video.getSrc();           // 当前源 URL
video.getVolume();        // 音量 0.0–1.0
video.isMuted();          // 是否静音
video.isLoop();           // 是否循环
video.isAutoplay();       // autoplay 是否启用
```

### 属性设置

```javascript
video.setVolume(0.5);
video.setMuted(true);
video.setMuted(false);
video.setLoop(true);
```

### 网络选项

```javascript
video.setUserAgent("Mozilla/5.0 ...");
video.setHeaders("accept: */*\r\nreferer: https://example.com/\r\n");
video.setNetworkOption("reconnect", "1");
video.clearNetworkOptions();
var opts = video.getNetworkOptions();
```

### HLS 变体选择

元素上直接调用，自动委托到 HlsMasterPlaylist 工具类：

```javascript
var url = video.hlsHighest("https://example.com/playlist.m3u8");
var url = video.hlsHighest("https://example.com/playlist.m3u8", 8000);
var url = video.hlsLowest("https://example.com/playlist.m3u8");
var url = video.hlsLowest("https://example.com/playlist.m3u8", 8000);
var url = video.hlsSelect("https://example.com/playlist.m3u8", "highest_resolution");
var url = video.hlsSelect("https://example.com/playlist.m3u8", "lowest", 8000);
```

policy 可选值：`highest`、`lowest`、`highest_resolution`、`lowest_resolution`

---

## B站直播流接入

通过 `ApricityMediaHls` 全局对象调用，自动解析房间号 → 获取实时流地址：

```javascript
// 默认原画（qn=10000）
var streamUrl = ApricityMediaHls.getBilibiliPlayUrl("22603245");

// 指定画质：80=流畅 150=高清 250=超清 400=蓝光 10000=原画
var streamUrl = ApricityMediaHls.getBilibiliPlayUrl("22603245", 400);

if (streamUrl) {
  video.setAttribute("src", streamUrl);
  video.play();
} else {
  console.log("获取直播流失败（房间未开播或网络错误）");
}
```

内部自动处理：短号→真实房间号解析、开播状态检查、CDN 线路选择。

---

## 媒体事件

| 事件 | 触发时机 |
| --- | --- |
| `play` | 开始播放 |
| `pause` | 暂停 |
| `ended` | 播放到末尾 |
| `timeupdate` | 播放中周期性触发（~250ms） |
| `loadedmetadata` | 元数据就绪 |
| `durationchange` | duration 变化 |
| `canplay` | 可开始播放 |
| `volumechange` | 音量/静音变化 |
| `error` | 解码/播放出错 |

```javascript
video.addEventListener("timeupdate", function() {
  var cur = video.getCurrentTime();
  var dur = video.getDuration();
  console.log(cur.toFixed(1) + " / " + dur.toFixed(1));
});

video.addEventListener("ended", function() {
  console.log("播放完毕");
});
```

---

## 完整示例

```html
<video id="player" src="demo.mp4" loop muted></video>

<script>
  var video = document.querySelector("#player");

  video.addEventListener("loadedmetadata", function() {
    console.log("时长: " + video.getDuration().toFixed(1) + " 秒");
    video.play();
  });

  video.addEventListener("timeupdate", function() {
    var pct = video.getDuration() > 0
      ? (video.getCurrentTime() / video.getDuration() * 100).toFixed(0)
      : 0;
    console.log("进度: " + pct + "%");
  });

  // 加载 B站直播流
  var url = ApricityMediaHls.getBilibiliPlayUrl("22603245");
  if (url) {
    video.setUserAgent("Mozilla/5.0 ...");
    video.setHeaders("referer: https://live.bilibili.com/\r\n");
    video.setAttribute("src", url);
    video.play();
  }
</script>
```

---

## 注意事项

1. 对于低配设备或极高分辨率视频，可设置 `decode-width` 控制解码分辨率
2. 高质量流（如 B站直播）无需锁帧锁分辨率，保持默认 `max-fps="0"` 即可
3. `drop-frames="true"`（默认）可在性能不足时自动跳帧，一般无需修改
4. HLS 日志中的 `Opening '...ts' for reading` 是正常的分片拉流行为
5. 直播流 `duration` 可能为 `-1`，JS 中应做 NaN 检查
6. `currentTime` 基于帧 PTS 估算，直播流精度有限
