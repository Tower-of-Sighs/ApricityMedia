package cc.sighs.apricitymedia;

import cc.sighs.apricitymedia.audio.IAudioDecoder;
import cc.sighs.apricitymedia.audio.JniAudioDecoder;
import cc.sighs.apricitymedia.jni.ApricityMediaNative;
import cc.sighs.apricitymedia.jni.NativeLibraryLoader;
import cc.sighs.apricitymedia.video.IVideoDecoder;
import cc.sighs.apricitymedia.video.JniVideoDecoder;
import net.minecraftforge.fml.loading.FMLLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

public final class FFmpegRuntimeBootstrap {
    private static final Logger LOGGER = LoggerFactory.getLogger(FFmpegRuntimeBootstrap.class);

    private static final String DIST_MARKER_RESOURCE = "META-INF/apricitymedia/dist.properties";
    private static final String DIST_MARKER_SALT = "AUIVideo-Dist-v1";
    private static final String RUNTIME_CONFIG_FILE = "apricitymedia-runtime.properties";

    private static final int PREWARM_MAX_ATTEMPTS = 6;

    private static boolean initialized = false;
    private static RuntimeException initError;
    private static Thread prewarmThread;
    private static boolean prewarmStarted = false;
    private static String initErrorMessage = "";

    private FFmpegRuntimeBootstrap() {}

    public static boolean ensureReady() {
        if (initialized) return true;
        if (waitForPrewarmIfRunning()) return initialized;
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

    // ---------------------------------------------------------------
    //  Public API
    // ---------------------------------------------------------------

    public static void prewarmAsync() {
        synchronized (FFmpegRuntimeBootstrap.class) {
            if (initialized || prewarmStarted) return;
            prewarmStarted = true;
            prewarmThread = new Thread(FFmpegRuntimeBootstrap::runPrewarmLoop, "ApricityMedia-FFmpeg-Prewarm");
            prewarmThread.setDaemon(true);
            prewarmThread.start();
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

    public static IVideoDecoder createVideoDecoder(String filePath, int targetWidth, int targetHeight,
                                                    double maxFps, int networkTimeoutMs, int networkBufferKb,
                                                    boolean networkReconnect, Map<String, String> networkOptions) {
        if (!ensureReady()) return null;
        try {
            return new JniVideoDecoder(filePath, targetWidth, targetHeight,
                    maxFps, networkTimeoutMs, networkBufferKb, networkReconnect, networkOptions);
        } catch (Exception e) {
            LOGGER.error("Failed to create JNI video decoder", e);
            return null;
        }
    }

    public static boolean hasInitFailure() {
        return !initialized && initError != null;
    }

    public static String getInitErrorMessage() {
        return initErrorMessage == null ? "" : initErrorMessage;
    }

    // ---------------------------------------------------------------
    //  Factory methods — direct constructor (no reflection)
    // ---------------------------------------------------------------

    public static IAudioDecoder createAudioDecoder(String filePath, int networkTimeoutMs,
                                                    int networkBufferKb, boolean networkReconnect,
                                                    Map<String, String> networkOptions) {
        if (!ensureReady()) return null;
        try {
            return new JniAudioDecoder(filePath, networkTimeoutMs, networkBufferKb,
                    networkReconnect, networkOptions);
        } catch (Exception e) {
            LOGGER.error("Failed to create JNI audio decoder", e);
            return null;
        }
    }

    private static void initializeInternal() {
        String distribution = detectDistribution();
        LOGGER.info("FFmpeg runtime init distribution={}", distribution);

        if (!NativeLibraryLoader.ensureLoaded()) {
            throw new IllegalStateException("Native library load failed: " + NativeLibraryLoader.getLoadError());
        }

        ApricityMediaNative.init();
        initialized = true;
        initError = null;
        initErrorMessage = "";
    }

    // ---------------------------------------------------------------
    //  Internal initialization
    // ---------------------------------------------------------------

    private static boolean isPrewarmRunning() {
        Thread thread;
        synchronized (FFmpegRuntimeBootstrap.class) { thread = prewarmThread; }
        return thread != null && thread.isAlive();
    }

    private static boolean waitForPrewarmIfRunning() {
        Thread thread;
        synchronized (FFmpegRuntimeBootstrap.class) { thread = prewarmThread; }
        if (thread == null || !thread.isAlive()) return false;
        boolean interrupted = false;
        while (thread.isAlive()) {
            try { thread.join(50L); }
            catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
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
                    LOGGER.error("FFmpeg runtime prewarm failed after {} attempts: {}",
                            PREWARM_MAX_ATTEMPTS, errorMessageOf(e), e);
                }
                if (attempt < PREWARM_MAX_ATTEMPTS) {
                    sleepQuietly(Math.min(10_000L, 1200L * attempt));
                }
            }
        }
    }

    private static void initializeBlockingOnBootstrapThread() {
        final boolean[] result = {false};
        Thread thread = new Thread(() -> result[0] = ensureReady(), "ApricityMedia-FFmpeg-BlockingInit");
        thread.setDaemon(false);
        thread.start();
        boolean interrupted = false;
        while (thread.isAlive()) {
            try { thread.join(); }
            catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
        if (!result[0]) {
            LOGGER.error("FFmpeg runtime startup blocking init failed: {}", getInitErrorMessage());
        }
    }

    private static RuntimeConfig loadRuntimeConfig() {
        Path configPath = FMLLoader.getGamePath().resolve("config").resolve(RUNTIME_CONFIG_FILE);
        Properties properties = new Properties();
        properties.setProperty("startup.blocking", "false");
        try {
            if (Files.isRegularFile(configPath)) {
                try (InputStream in = Files.newInputStream(configPath)) { properties.load(in); }
            } else {
                Files.createDirectories(configPath.getParent());
                Files.writeString(configPath, "startup.blocking=false\n", StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            LOGGER.warn("FFmpeg runtime config read failed, using defaults: {}", errorMessageOf(e));
        }
        return new RuntimeConfig("true".equalsIgnoreCase(properties.getProperty("startup.blocking", "").trim()));
    }

    // ---------------------------------------------------------------
    //  Config and dist detection
    // ---------------------------------------------------------------

    private static String detectDistribution() {
        try (InputStream in = FFmpegRuntimeBootstrap.class.getClassLoader().getResourceAsStream(DIST_MARKER_RESOURCE)) {
            if (in == null) return "full";
            Properties properties = new Properties();
            properties.load(in);
            String raw = properties.getProperty("distribution", "");
            String dist = raw.trim().toLowerCase(Locale.ROOT);
            String nonce = properties.getProperty("nonce", "").trim();
            String checksum = properties.getProperty("checksum", "").trim().toLowerCase(Locale.ROOT);
            if (dist.isBlank() || nonce.isBlank() || checksum.isBlank()) return "full";
            String expected = sha256Hex(dist + ":" + nonce + ":" + DIST_MARKER_SALT);
            if (!checksum.equals(expected)) return "full";
            return dist;
        } catch (Exception e) {
            return "full";
        }
    }

    private static String errorMessageOf(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && !message.isBlank()) return message;
            current = current.getCause();
        }
        return "unknown error";
    }

    // ---------------------------------------------------------------
    //  Utilities
    // ---------------------------------------------------------------

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

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); }
        catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    private record RuntimeConfig(boolean startupBlocking) {}
}
