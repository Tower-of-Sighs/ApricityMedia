package cc.sighs.apricitymedia;

import cc.sighs.apricitymedia.hack.FixedModularURLHandler;
import net.lenni0451.reflect.Fields;
import net.minecraftforge.fml.loading.FMLLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLStreamHandler;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

public final class FFmpegRuntimeBootstrap {
    private static final Logger LOGGER = LoggerFactory.getLogger(FFmpegRuntimeBootstrap.class);
    private static final String JAVACPP_VERSION = "1.5.13";
    private static final String FFMPEG_VERSION = "8.0.1-1.5.13";
    private static final String MAVEN_BASE = "https://repo1.maven.org/maven2/org/bytedeco";

    private static final Set<String> CLASS_PATH_ADDED = new HashSet<>();
    private static final int DOWNLOAD_RETRY_COUNT = 3;
    private static final int PREWARM_MAX_ATTEMPTS = 6;
    private static boolean initialized = false;
    private static RuntimeException initError;
    private static Thread prewarmThread;
    private static boolean prewarmStarted = false;
    private static String initErrorMessage = "";

    private FFmpegRuntimeBootstrap() {
    }

    public static boolean ensureReady() {
        if (initialized) return true;
        if (isPrewarmRunning()) {
            initErrorMessage = "FFmpeg runtime preparing in background";
            return false;
        }
        synchronized (FFmpegRuntimeBootstrap.class) {
            if (initialized) return true;
            try {
                LOGGER.info("FFmpeg runtime ensureReady start");
                initializeInternal();
                LOGGER.info("FFmpeg runtime ensureReady success");
            } catch (Exception e) {
                initError = new IllegalStateException("Failed to initialize FFmpeg runtime", e);
                initErrorMessage = errorMessageOf(e);
                LOGGER.error("FFmpeg runtime ensureReady failed: {}", initErrorMessage, e);
                return false;
            }
            return true;
        }
    }

    public static void prewarmAsync() {
        synchronized (FFmpegRuntimeBootstrap.class) {
            if (initialized || prewarmStarted) return;
            prewarmStarted = true;
            prewarmThread = new Thread(FFmpegRuntimeBootstrap::runPrewarmLoop, "ApricityUI-FFmpeg-Prewarm");
            prewarmThread.setDaemon(true);
            prewarmThread.start();
        }
    }

    public static boolean hasInitFailure() {
        return !initialized && initError != null;
    }

    public static String getInitErrorMessage() {
        return initErrorMessage == null ? "" : initErrorMessage;
    }

    private static boolean isPrewarmRunning() {
        Thread thread;
        synchronized (FFmpegRuntimeBootstrap.class) {
            thread = prewarmThread;
        }
        return thread != null && thread.isAlive();
    }

    private static void runPrewarmLoop() {
        for (int attempt = 1; attempt <= PREWARM_MAX_ATTEMPTS; attempt++) {
            try {
                synchronized (FFmpegRuntimeBootstrap.class) {
                    if (initialized) return;
                    initializeInternal();
                    LOGGER.info("FFmpeg runtime prewarm success at attempt {}", attempt);
                    return;
                }
            } catch (Exception e) {
                synchronized (FFmpegRuntimeBootstrap.class) {
                    initError = new IllegalStateException("Failed to initialize FFmpeg runtime", e);
                    initErrorMessage = errorMessageOf(e);
                }
                LOGGER.warn("FFmpeg runtime prewarm attempt {} failed: {}", attempt, errorMessageOf(e), e);
                if (attempt < PREWARM_MAX_ATTEMPTS) {
                    sleepQuietly(Math.min(10_000L, 1200L * attempt));
                }
            }
        }
    }

    private static void initializeInternal() throws Exception {
        String distribution = detectDistribution();
        if (distribution.startsWith("downloader")) {
            prepareDownloaderRuntime();
        }
        loadNatives();
        initialized = true;
        initError = null;
        initErrorMessage = "";
    }

    private static void prepareDownloaderRuntime() throws Exception {
        String platform = resolvePlatformClassifier();
        Path runtimeDir = FMLLoader.getGamePath()
                                   .resolve(".apricityui-video")
                                   .resolve("runtime")
                                   .resolve(FFMPEG_VERSION);
        Files.createDirectories(runtimeDir);

        String javacppName = "javacpp-" + JAVACPP_VERSION + "-" + platform + ".jar";
        String ffmpegName = "ffmpeg-" + FFMPEG_VERSION + "-" + platform + ".jar";

        Path javacppJar = runtimeDir.resolve(javacppName);
        Path ffmpegJar = runtimeDir.resolve(ffmpegName);

        LOGGER.info("FFmpeg runtime downloader target platform={}, dir={}", platform, runtimeDir);
        downloadIfMissing(javacppJar, MAVEN_BASE + "/javacpp/" + JAVACPP_VERSION + "/" + javacppName);
        downloadIfMissing(ffmpegJar, MAVEN_BASE + "/ffmpeg/" + FFMPEG_VERSION + "/" + ffmpegName);

        addToRuntimeClassPath(javacppJar);
        addToRuntimeClassPath(ffmpegJar);
    }

    private static void loadNatives() throws Exception {
        //see: https://github.com/bytedeco/javacpp/issues/697
        Class.forName("cc.sighs.apricitymedia.hack.FixedModularURLHandler$FunctionURLStreamHandler", true, FFmpegRuntimeBootstrap.class.getClassLoader());
        Class.forName("cc.sighs.apricitymedia.hack.FixedModularURLHandler$FunctionURLConnection", true, FFmpegRuntimeBootstrap.class.getClassLoader());
        Class.forName("cc.sighs.apricitymedia.hack.FixedModularURLHandler$FixedURLProvider", true, FFmpegRuntimeBootstrap.class.getClassLoader());
        Class.forName("cc.sighs.apricitymedia.hack.FixedUnionURLStreamHandler", true, FFmpegRuntimeBootstrap.class.getClassLoader());
        Field factory = URL.class.getDeclaredField("factory");
        Field handlers = URL.class.getDeclaredField("handlers");
        FixedModularURLHandler.init();
        Fields.set(null, factory, FixedModularURLHandler.INSTANCE);
        Hashtable<String, URLStreamHandler> handlersTable = Fields.get(null, handlers);
        handlersTable.clear();
        Class<?> loaderClass = Class.forName("org.bytedeco.javacpp.Loader");
        Method loadMethod = loaderClass.getMethod("load", Class.class);
        loadMethod.invoke(null, Class.forName("org.bytedeco.ffmpeg.global.avutil"));
        loadMethod.invoke(null, Class.forName("org.bytedeco.ffmpeg.global.avcodec"));
        loadMethod.invoke(null, Class.forName("org.bytedeco.ffmpeg.global.avformat"));
        loadMethod.invoke(null, Class.forName("org.bytedeco.ffmpeg.global.swresample"));
        loadMethod.invoke(null, Class.forName("org.bytedeco.ffmpeg.global.swscale"));
//        Fields.set(null, factory, ModularURLHandler.INSTANCE);
//        handlersTable.clear();
    }

    private static void addToRuntimeClassPath(Path jarPath) throws Exception {
        if (jarPath == null) return;
        Path absolute = jarPath.toAbsolutePath().normalize();
        String key = absolute.toString();
        synchronized (CLASS_PATH_ADDED) {
            if (CLASS_PATH_ADDED.contains(key)) return;
            boolean added = tryAddToKnownClassLoaders(absolute);
            if (!added) {
                throw new IllegalStateException("Unable to add jar to runtime classpath: " + absolute);
            }
            LOGGER.info("FFmpeg runtime classpath added {}", absolute);
            CLASS_PATH_ADDED.add(key);
        }
    }

    private static boolean tryAddToKnownClassLoaders(Path absolute) {
        Set<ClassLoader> loaders = new HashSet<>();
        loaders.add(Thread.currentThread().getContextClassLoader());
        loaders.add(FFmpegRuntimeBootstrap.class.getClassLoader());
        loaders.add(FMLLoader.class.getClassLoader());
        loaders.add(ClassLoader.getSystemClassLoader());
        for (ClassLoader loader : loaders) {
            if (tryAddToTargetLoader(loader, absolute)) {
                return true;
            }
        }
        return false;
    }

    private static boolean tryAddToTargetLoader(ClassLoader loader, Path absolute) {
        if (loader == null) return false;
        URL url;
        try {
            url = absolute.toUri().toURL();
        } catch (Exception ignored) {
            return false;
        }
        try {
            Method method = loader.getClass().getMethod("addUrlFwd", URL.class);
            method.invoke(loader, url);
            return true;
        } catch (Exception ignored) {
        }
        try {
            Method method = loader.getClass().getDeclaredMethod("addURL", URL.class);
            method.setAccessible(true);
            method.invoke(loader, url);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void downloadIfMissing(Path target, String url) throws Exception {
        if (Files.isRegularFile(target) && Files.size(target) > 0) return;
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Exception lastError = null;
        for (int attempt = 1; attempt <= DOWNLOAD_RETRY_COUNT; attempt++) {
            try {
                Files.deleteIfExists(temp);
                HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
                HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(90)).GET().build();
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();
                if (status < 200 || status >= 300) {
                    throw new IllegalStateException("Download failed: " + url + " status=" + status);
                }
                try (InputStream in = response.body()) {
                    Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
                }
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                LOGGER.info("FFmpeg runtime download success {}", target.getFileName());
                return;
            } catch (Exception e) {
                lastError = e;
                LOGGER.warn("FFmpeg runtime download attempt {}/{} failed for {}: {}", attempt, DOWNLOAD_RETRY_COUNT, target.getFileName(), errorMessageOf(e));
                try {
                    Files.deleteIfExists(temp);
                } catch (Exception ignored) {
                }
                if (attempt < DOWNLOAD_RETRY_COUNT) {
                    sleepQuietly(800L * attempt);
                }
            }
        }
        throw new IllegalStateException("Download failed after retries: " + url, lastError);
    }

    private static String resolvePlatformClassifier() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (os.contains("win")) return "windows-x86_64";
        if (os.contains("mac") || os.contains("darwin")) {
            if (arch.contains("aarch64") || arch.contains("arm64")) return "macosx-arm64";
            return "macosx-x86_64";
        }
        if (os.contains("linux")) {
            if (arch.contains("aarch64") || arch.contains("arm64")) return "linux-arm64";
            return "linux-x86_64";
        }
        throw new IllegalStateException("Unsupported platform: os=" + os + ", arch=" + arch);
    }

    private static String detectDistribution() {
        try {
            URL location = ApricityMedia.class.getProtectionDomain().getCodeSource().getLocation();
            if (location == null) return "full";
            Path path = Path.of(location.toURI());
            if (!Files.isRegularFile(path)) return "full";
            try (JarFile jarFile = new JarFile(path.toFile())) {
                Manifest manifest = jarFile.getManifest();
                if (manifest == null) return "full";
                Attributes attributes = manifest.getMainAttributes();
                String value = attributes.getValue("AUIVideo-Distribution");
                if (value == null || value.isBlank()) return "full";
                return value.trim().toLowerCase();
            }
        } catch (Exception ignored) {
            return "full";
        }
    }

    private static String errorMessageOf(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && !message.isBlank()) {
                return message;
            }
            current = current.getCause();
        }
        return "unknown error";
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
