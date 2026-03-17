package cc.sighs.apricitymedia.video;

import cc.sighs.apricitymedia.FFmpegRuntimeBootstrap;
import com.sighs.apricityui.instance.Loader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class VideoPlayer implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(VideoPlayer.class);
    private final String source;
    private final Path cleanupFile;
    private final boolean loop;
    private final int targetWidth;
    private final int targetHeight;
    private final double maxFps;
    private final int networkTimeoutMs;
    private final int networkBufferKb;
    private final boolean networkReconnect;
    private final ExecutorService executor;
    private final BlockingQueue<VideoFrame> frames;

    private volatile boolean closed = false;

    private VideoPlayer(String source, Path cleanupFile, boolean loop, int targetWidth, int targetHeight, double maxFps, int queueSize, int networkTimeoutMs, int networkBufferKb, boolean networkReconnect) {
        this.source = source;
        this.cleanupFile = cleanupFile;
        this.loop = loop;
        this.targetWidth = targetWidth;
        this.targetHeight = targetHeight;
        this.maxFps = maxFps;
        this.networkTimeoutMs = networkTimeoutMs;
        this.networkBufferKb = networkBufferKb;
        this.networkReconnect = networkReconnect;
        this.frames = new ArrayBlockingQueue<>(Math.max(1, queueSize));
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread t = new Thread(runnable, "ApricityUI-VideoDecoder");
            t.setDaemon(true);
            return t;
        });
        this.executor.execute(this::decodeLoop);
    }

    public static VideoPlayer open(String resolvedPath, boolean loop) {
        return open(resolvedPath, loop, -1, -1, 0, 8, 15000, 512, true);
    }

    public static VideoPlayer open(String resolvedPath, boolean loop, int targetWidth, int targetHeight, double maxFps, int queueSize) {
        return open(resolvedPath, loop, targetWidth, targetHeight, maxFps, queueSize, 15000, 512, true);
    }

    public static VideoPlayer open(String resolvedPath, boolean loop, int targetWidth, int targetHeight, double maxFps, int queueSize, int networkTimeoutMs, int networkBufferKb, boolean networkReconnect) {
        if (!FFmpegRuntimeBootstrap.ensureReady()) {
            LOGGER.warn("Open video playback skipped, FFmpeg runtime not ready: {}", FFmpegRuntimeBootstrap.getInitErrorMessage());
            return null;
        }
        SourceHandle handle = prepareSource(resolvedPath);
        if (handle == null || handle.source == null || handle.source.isBlank()) return null;
        return new VideoPlayer(handle.source, handle.cleanupFile, loop, targetWidth, targetHeight, maxFps, queueSize, networkTimeoutMs, networkBufferKb, networkReconnect);
    }

    public VideoFrame pollFrame() {
        return frames.poll();
    }

    @Override
    public void close() {
        closed = true;
        executor.shutdownNow();
        try {
            executor.awaitTermination(200, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        frames.clear();
        try {
            if (cleanupFile != null) {
                Files.deleteIfExists(cleanupFile);
            }
        } catch (Exception e) {
            LOGGER.warn("Cleanup video temp file failed for source={}", source, e);
        }
    }

    private void decodeLoop() {
        try (FFmpegVideoDecoder decoder = new FFmpegVideoDecoder(source, targetWidth, targetHeight, maxFps, networkTimeoutMs, networkBufferKb, networkReconnect)) {
            while (!closed) {
                if (frames.remainingCapacity() == 0) {
                    sleep(2);
                    continue;
                }

                VideoFrame frame = decoder.readNextFrame();
                if (frame == null) {
                    if (!loop) {
                        sleep(10);
                        continue;
                    }
                    decoder.rewind();
                    continue;
                }
                frames.offer(frame);
            }
        } catch (Exception e) {
            LOGGER.error("Video decode loop failed for source={}", source, e);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static SourceHandle prepareSource(String resolvedPath) {
        if (isRemotePath(resolvedPath)) {
            return new SourceHandle(resolvedPath, null);
        }
        try (InputStream is = Loader.getResourceStream(resolvedPath)) {
            if (is == null) return null;
            Path tmp = Files.createTempFile("apricityui-video-", "-" + sanitize(resolvedPath));
            byte[] buffer = new byte[64 * 1024];
            try (var out = Files.newOutputStream(tmp)) {
                int read;
                while ((read = is.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
            }
            tmp.toFile().deleteOnExit();
            return new SourceHandle(tmp.toAbsolutePath().toString(), tmp);
        } catch (Exception e) {
            LOGGER.warn("Prepare video source failed for path={}", resolvedPath, e);
            return null;
        }
    }

    private static boolean isRemotePath(String path) {
        if (path == null) return false;
        String v = path.trim().toLowerCase();
        return v.startsWith("http://")
                || v.startsWith("https://")
                || v.startsWith("rtsp://")
                || v.startsWith("rtmp://")
                || v.startsWith("mms://");
    }

    private static String sanitize(String value) {
        if (value == null) return "video";
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private record SourceHandle(String source, Path cleanupFile) {
    }
}
