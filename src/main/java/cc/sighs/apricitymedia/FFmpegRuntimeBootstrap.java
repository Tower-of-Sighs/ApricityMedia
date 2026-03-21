package cc.sighs.apricitymedia;

import cc.sighs.apricitymedia.hack.FixedModularURLHandler;
import net.minecraftforge.fml.loading.FMLLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sun.misc.Unsafe;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLStreamHandler;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Hashtable;
import java.util.Locale;
import java.util.Properties;


public final class FFmpegRuntimeBootstrap {
    private static final Logger LOGGER = LoggerFactory.getLogger(FFmpegRuntimeBootstrap.class);
    private static final String JAVACPP_VERSION = "1.5.13";
    private static final String FFMPEG_VERSION = "8.0.1-1.5.13";
    private static final String MAVEN_BASE = "https://repo1.maven.org/maven2/org/bytedeco";
    private static final String DIST_MARKER_RESOURCE = "META-INF/apricitymedia/dist.properties";
    private static final String DIST_MARKER_SALT = "AUIVideo-Dist-v1";
    private static final String RUNTIME_CONFIG_FILE = "apricitymedia-runtime.properties";
    private static final String BUNDLED_RUNTIME_DIR = "META-INF/apricitymedia/runtime/";

    private static final int DOWNLOAD_RETRY_COUNT = 3;
    private static final int PREWARM_MAX_ATTEMPTS = 6;
    private static final Unsafe UNSAFE = initUnsafe();
    private static boolean initialized = false;
    private static RuntimeException initError;
    private static Thread prewarmThread;
    private static boolean prewarmStarted = false;
    private static String initErrorMessage = "";
    private static Path runtimeJavacppBaseJar;
    private static Path runtimeFfmpegBaseJar;
    private static Path runtimeJavacppPlatformJar;
    private static Path runtimeFfmpegPlatformJar;
    private static ClassLoader runtimeDownloaderClassLoader;

    private record RuntimeConfig(boolean startupBlocking) {
    }

    private FFmpegRuntimeBootstrap() {
    }

    public static boolean ensureReady() {
        if (initialized) return true;
        if (waitForPrewarmIfRunning()) {
            return initialized;
        }
        synchronized (FFmpegRuntimeBootstrap.class) {
            if (initialized) return true;
            try {
                initializeInternal();
            } catch (Exception e) {
                initError = new IllegalStateException("Failed to initialize FFmpeg runtime", e);
                initErrorMessage = errorMessageOf(e);
                LOGGER.error("FFmpeg runtime ensureReady failed: {}", initErrorMessage, e);
                return false;
            }
            return true;
        }
    }

    public static void initializeOnStartup() {
        RuntimeConfig config = loadRuntimeConfig();
        if (config.startupBlocking()) {
            initializeBlockingOnBootstrapThread();
            return;
        }
        prewarmAsync();
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

    private static boolean waitForPrewarmIfRunning() {
        Thread thread;
        synchronized (FFmpegRuntimeBootstrap.class) {
            thread = prewarmThread;
        }
        if (thread == null || !thread.isAlive()) return false;
        boolean interrupted = false;
        while (thread.isAlive()) {
            try {
                thread.join(50L);
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return true;
    }

    private static void runPrewarmLoop() {
        for (int attempt = 1; attempt <= PREWARM_MAX_ATTEMPTS; attempt++) {
            try {
                synchronized (FFmpegRuntimeBootstrap.class) {
                    if (initialized) return;
                    initializeInternal();
                    return;
                }
            } catch (Exception e) {
                synchronized (FFmpegRuntimeBootstrap.class) {
                    initError = new IllegalStateException("Failed to initialize FFmpeg runtime", e);
                    initErrorMessage = errorMessageOf(e);
                }
                if (attempt >= PREWARM_MAX_ATTEMPTS) {
                    LOGGER.error("FFmpeg runtime prewarm failed after {} attempts: {}", PREWARM_MAX_ATTEMPTS, errorMessageOf(e), e);
                }
                if (attempt < PREWARM_MAX_ATTEMPTS) {
                    sleepQuietly(Math.min(10_000L, 1200L * attempt));
                }
            }
        }
    }

    private static void initializeInternal() throws Exception {
        String distribution = detectDistribution();
        boolean downloader = distribution.startsWith("downloader");
        boolean externalRuntime = false;
        LOGGER.info("FFmpeg runtime init distribution={}", distribution);
        if (downloader) {
            prepareDownloaderRuntime();
            externalRuntime = true;
        } else if (prepareBundledRuntime()) {
            externalRuntime = true;
        }
        LOGGER.info("FFmpeg runtime init externalRuntimeLoader={}", externalRuntime);
        loadNatives(externalRuntime);
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

        String javacppBaseName = "javacpp-" + JAVACPP_VERSION + ".jar";
        String ffmpegBaseName = "ffmpeg-" + FFMPEG_VERSION + ".jar";
        String javacppName = "javacpp-" + JAVACPP_VERSION + "-" + platform + ".jar";
        String ffmpegName = "ffmpeg-" + FFMPEG_VERSION + "-" + platform + ".jar";

        Path javacppBaseJar = runtimeDir.resolve(javacppBaseName);
        Path ffmpegBaseJar = runtimeDir.resolve(ffmpegBaseName);
        Path javacppJar = runtimeDir.resolve(javacppName);
        Path ffmpegJar = runtimeDir.resolve(ffmpegName);

        downloadIfMissing(javacppBaseJar, MAVEN_BASE + "/javacpp/" + JAVACPP_VERSION + "/" + javacppBaseName);
        downloadIfMissing(ffmpegBaseJar, MAVEN_BASE + "/ffmpeg/" + FFMPEG_VERSION + "/" + ffmpegBaseName);
        downloadIfMissing(javacppJar, MAVEN_BASE + "/javacpp/" + JAVACPP_VERSION + "/" + javacppName);
        downloadIfMissing(ffmpegJar, MAVEN_BASE + "/ffmpeg/" + FFMPEG_VERSION + "/" + ffmpegName);
        runtimeJavacppBaseJar = javacppBaseJar;
        runtimeFfmpegBaseJar = ffmpegBaseJar;
        runtimeJavacppPlatformJar = javacppJar;
        runtimeFfmpegPlatformJar = ffmpegJar;
        runtimeDownloaderClassLoader = null;
        LOGGER.info("FFmpeg runtime prepared downloader jars at {}", runtimeDir);
    }

    private static boolean prepareBundledRuntime() throws Exception {
        String platform = resolvePlatformClassifier();
        String javacppBaseName = "javacpp-" + JAVACPP_VERSION + ".jar";
        String ffmpegBaseName = "ffmpeg-" + FFMPEG_VERSION + ".jar";
        String javacppPlatformName = "javacpp-" + JAVACPP_VERSION + "-" + platform + ".jar";
        String ffmpegPlatformName = "ffmpeg-" + FFMPEG_VERSION + "-" + platform + ".jar";
        if (!hasBundledRuntimeResource(javacppBaseName) || !hasBundledRuntimeResource(ffmpegBaseName) ||
                !hasBundledRuntimeResource(javacppPlatformName) || !hasBundledRuntimeResource(ffmpegPlatformName)) {
            return false;
        }
        Path runtimeDir = FMLLoader.getGamePath().resolve(".apricityui-video").resolve("runtime").resolve(FFMPEG_VERSION).resolve("bundled");
        Files.createDirectories(runtimeDir);
        Path javacppBaseJar = runtimeDir.resolve(javacppBaseName);
        Path ffmpegBaseJar = runtimeDir.resolve(ffmpegBaseName);
        Path javacppPlatformJar = runtimeDir.resolve(javacppPlatformName);
        Path ffmpegPlatformJar = runtimeDir.resolve(ffmpegPlatformName);
        copyBundledRuntimeIfMissing(javacppBaseName, javacppBaseJar);
        copyBundledRuntimeIfMissing(ffmpegBaseName, ffmpegBaseJar);
        copyBundledRuntimeIfMissing(javacppPlatformName, javacppPlatformJar);
        copyBundledRuntimeIfMissing(ffmpegPlatformName, ffmpegPlatformJar);
        runtimeJavacppBaseJar = javacppBaseJar;
        runtimeFfmpegBaseJar = ffmpegBaseJar;
        runtimeJavacppPlatformJar = javacppPlatformJar;
        runtimeFfmpegPlatformJar = ffmpegPlatformJar;
        runtimeDownloaderClassLoader = null;
        LOGGER.info("FFmpeg runtime prepared bundled jars at {}", runtimeDir);
        return true;
    }

    private static void loadNatives(boolean useExternalRuntimeLoader) throws Exception {
        //see: https://github.com/bytedeco/javacpp/issues/697
        Class.forName("cc.sighs.apricitymedia.hack.FixedModularURLHandler$FunctionURLStreamHandler", true, FFmpegRuntimeBootstrap.class.getClassLoader());
        Class.forName("cc.sighs.apricitymedia.hack.FixedModularURLHandler$FunctionURLConnection", true, FFmpegRuntimeBootstrap.class.getClassLoader());
        Class.forName("cc.sighs.apricitymedia.hack.FixedModularURLHandler$FixedURLProvider", true, FFmpegRuntimeBootstrap.class.getClassLoader());
        Class.forName("cc.sighs.apricitymedia.hack.FixedUnionURLStreamHandler", true, FFmpegRuntimeBootstrap.class.getClassLoader());
        Field factoryField = URL.class.getDeclaredField("factory");
        Field handlersField = URL.class.getDeclaredField("handlers");
        FixedModularURLHandler.init();
        setStaticField(factoryField, FixedModularURLHandler.INSTANCE);
        Hashtable<String, URLStreamHandler> handlersTable = getStaticField(handlersField);
        handlersTable.clear();
        ClassLoader runtimeLoader = useExternalRuntimeLoader ? getOrCreateDownloaderClassLoader() : FFmpegRuntimeBootstrap.class.getClassLoader();
        Class<?> loaderClass = Class.forName("org.bytedeco.javacpp.Loader", true, runtimeLoader);
        Method loadMethod = loaderClass.getMethod("load", Class.class);
        loadMethod.invoke(null, Class.forName("org.bytedeco.ffmpeg.global.avutil", true, runtimeLoader));
        loadMethod.invoke(null, Class.forName("org.bytedeco.ffmpeg.global.avcodec", true, runtimeLoader));
        loadMethod.invoke(null, Class.forName("org.bytedeco.ffmpeg.global.avformat", true, runtimeLoader));
        loadMethod.invoke(null, Class.forName("org.bytedeco.ffmpeg.global.swresample", true, runtimeLoader));
        loadMethod.invoke(null, Class.forName("org.bytedeco.ffmpeg.global.swscale", true, runtimeLoader));
    }

    @SuppressWarnings("unchecked")
    private static <T> T getStaticField(Field field) {
        Object base = UNSAFE.staticFieldBase(field);
        long offset = UNSAFE.staticFieldOffset(field);
        return (T) UNSAFE.getObject(base, offset);
    }

    private static void setStaticField(Field field, Object value) {
        Object base = UNSAFE.staticFieldBase(field);
        long offset = UNSAFE.staticFieldOffset(field);
        UNSAFE.putObject(base, offset, value);
    }

    private static Unsafe initUnsafe() {
        try {
            Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            return (Unsafe) unsafeField.get(null);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize Unsafe", e);
        }
    }

    private static boolean hasBundledRuntimeResource(String fileName) {
        try (InputStream in = FFmpegRuntimeBootstrap.class.getClassLoader().getResourceAsStream(BUNDLED_RUNTIME_DIR + fileName)) {
            return in != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void copyBundledRuntimeIfMissing(String fileName, Path target) throws Exception {
        if (Files.isRegularFile(target) && Files.size(target) > 0) return;
        Files.createDirectories(target.getParent());
        try (InputStream in = FFmpegRuntimeBootstrap.class.getClassLoader().getResourceAsStream(BUNDLED_RUNTIME_DIR + fileName)) {
            if (in == null) {
                throw new IllegalStateException("Bundled runtime resource missing: " + fileName);
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static ClassLoader getOrCreateDownloaderClassLoader() throws Exception {
        ClassLoader cached = runtimeDownloaderClassLoader;
        if (cached != null) return cached;
        Path javacppBaseJar = runtimeJavacppBaseJar;
        Path ffmpegBaseJar = runtimeFfmpegBaseJar;
        Path javacppPlatformJar = runtimeJavacppPlatformJar;
        Path ffmpegPlatformJar = runtimeFfmpegPlatformJar;
        if (javacppBaseJar == null || ffmpegBaseJar == null || javacppPlatformJar == null || ffmpegPlatformJar == null) {
            throw new IllegalStateException("Runtime jars not prepared for downloader distribution");
        }
        URL[] urls = new URL[]{
                javacppBaseJar.toAbsolutePath().normalize().toUri().toURL(),
                ffmpegBaseJar.toAbsolutePath().normalize().toUri().toURL(),
                javacppPlatformJar.toAbsolutePath().normalize().toUri().toURL(),
                ffmpegPlatformJar.toAbsolutePath().normalize().toUri().toURL()
        };
        synchronized (FFmpegRuntimeBootstrap.class) {
            if (runtimeDownloaderClassLoader == null) {
                runtimeDownloaderClassLoader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader());
            }
            return runtimeDownloaderClassLoader;
        }
    }

    private static void downloadIfMissing(Path target, String url) throws Exception {
        if (Files.isRegularFile(target) && Files.size(target) > 0) {
            LOGGER.info("FFmpeg runtime download skip, file already exists: {}", target);
            return;
        }
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Exception lastError = null;
        for (int attempt = 1; attempt <= DOWNLOAD_RETRY_COUNT; attempt++) {
            try {
                Files.deleteIfExists(temp);
                LOGGER.info("FFmpeg runtime downloading {} from {} (attempt {}/{})", target.getFileName(), url, attempt, DOWNLOAD_RETRY_COUNT);
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
        try (InputStream in = FFmpegRuntimeBootstrap.class.getClassLoader().getResourceAsStream(DIST_MARKER_RESOURCE)) {
            if (in == null) {
                return "full";
            }
            Properties properties = new Properties();
            properties.load(in);
            String rawDistribution = properties.getProperty("distribution", "");
            String distribution = rawDistribution.trim().toLowerCase(Locale.ROOT);
            String nonce = properties.getProperty("nonce", "").trim();
            String checksum = properties.getProperty("checksum", "").trim().toLowerCase(Locale.ROOT);
            if (distribution.isBlank() || nonce.isBlank() || checksum.isBlank()) {
                LOGGER.warn("FFmpeg runtime detectDistribution: invalid marker payload, defaulting to full");
                return "full";
            }
            String expected = sha256Hex(distribution + ":" + nonce + ":" + DIST_MARKER_SALT);
            if (!checksum.equals(expected)) {
                LOGGER.warn("FFmpeg runtime detectDistribution: marker checksum mismatch, defaulting to full");
                return "full";
            }
            return distribution;
        } catch (Exception e) {
            LOGGER.warn("FFmpeg runtime detectDistribution failed, defaulting to full: {}", errorMessageOf(e));
            return "full";
        }
    }

    private static RuntimeConfig loadRuntimeConfig() {
        Path configPath = FMLLoader.getGamePath().resolve("config").resolve(RUNTIME_CONFIG_FILE);
        Properties properties = new Properties();
        properties.setProperty("startup.blocking", "false");
        try {
            if (Files.isRegularFile(configPath)) {
                try (InputStream in = Files.newInputStream(configPath)) {
                    properties.load(in);
                }
            } else {
                Files.createDirectories(configPath.getParent());
                String text = "startup.blocking=false\n";
                Files.writeString(configPath, text, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            LOGGER.warn("FFmpeg runtime config read failed, using defaults: {}", errorMessageOf(e));
        }
        String raw = properties.getProperty("startup.blocking", "false");
        boolean startupBlocking = "true".equalsIgnoreCase(raw.trim());
        return new RuntimeConfig(startupBlocking);
    }

    private static void initializeBlockingOnBootstrapThread() {
        final boolean[] result = new boolean[]{false};
        Thread thread = new Thread(() -> result[0] = ensureReady(), "ApricityUI-FFmpeg-BlockingInit");
        thread.setDaemon(false);
        thread.start();
        boolean interrupted = false;
        while (thread.isAlive()) {
            try {
                thread.join();
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        if (!result[0]) {
            LOGGER.error("FFmpeg runtime startup blocking init failed: {}", getInitErrorMessage());
        }
    }

    private static String sha256Hex(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder(hash.length * 2);
        for (byte item : hash) {
            builder.append(Character.forDigit((item >> 4) & 0xF, 16));
            builder.append(Character.forDigit(item & 0xF, 16));
        }
        return builder.toString();
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
