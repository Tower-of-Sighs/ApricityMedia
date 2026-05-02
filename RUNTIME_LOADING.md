# 运行时加载机制说明

## 为什么不用 jarJar？

Forge 1.20.1 使用 Java 模块系统（ModuleClassLoader），jarJar 的 JAR 会被加载到模块隔离空间。而 JavaCPP 的 JAR 存在两个问题：

1. **module-info 冲突**：平台 JAR（如 `javacpp-windows-x86_64.jar`）包含 `module-info.class`，声明了 `requires org.bytedeco.javacpp`。放进 jarJar 后 Forge 的模块解析器会抛 `module.FindException`。

2. **ClassNotFoundException**：即使移除 module-info，jarJar 的 class 也不在 ModuleClassLoader 的 classpath 上。运行时会找不到 `org.bytedeco.javacpp.Loader`。

## 解决思路

所有 FFmpeg/JavaCPP JAR 避开 jarJar 系统，在运行时用 URLClassLoader 手动加载。

### 架构

```
┌─────────────────────────────────────────┐
│           ModuleClassLoader             │ ← Forge 主加载器
│  IVideoDecoder, IAudioDecoder (接口)    │
│  VideoPlayer, AudioPlayback (调用方)    │
│  FFmpegRuntimeBootstrap (工厂)          │
└────────────┬────────────────────────────┘
             │ parent 委托
┌────────────▼────────────────────────────┐
│           URLClassLoader                │ ← 运行时创建
│  FFmpegVideoDecoder, FFmpegAudioDecoder │
│  org.bytedeco.javacpp.Loader           │
│  org.bytedeco.ffmpeg.*                 │
└─────────────────────────────────────────┘
```

- `ModuleClassLoader` 持有接口（`IVideoDecoder`/`IAudioDecoder`），`URLClassLoader` 持有实现类和 FFmpeg 类型。两个类加载器都能看到接口，JNI 类型一致性由 URLClassLoader 保证。
- 工厂方法 `FFmpegRuntimeBootstrap.createVideoDecoder()` 通过反射在 URLClassLoader 中实例化解码器，返回接口引用。

### JAR 打包

- `FFmpegVideoDecoder.class`、`FFmpegAudioDecoder.class` 从主 JAR 排除，打包为 `decoder.jar`
- 所有 `javacpp-*.jar`、`ffmpeg-*.jar` 从 `META-INF/jarjar/` 移动到 `META-INF/apricitymedia/runtime/`
- 运行时 `FFmpegRuntimeBootstrap` 将以上 JAR 解压到临时目录，用 URLClassLoader 加载

## 不要改什么

- 不要把 FFmpeg/JavaCPP 放回 jarJar
- 不要把 decoder class 加回主 JAR
- 不要移除 build.gradle.kts 中的 `exclude` 和重命名逻辑
- 接口（`IVideoDecoder`、`IAudioDecoder`）必须在主 classpath 上，不能在 decoder.jar 里
