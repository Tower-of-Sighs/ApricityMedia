package cc.sighs.apricitymedia.video;

import cc.sighs.apricitymedia.FFmpegRuntimeBootstrap;
import com.sighs.apricityui.instance.Loader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.*;

public final class VideoPlayer implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(VideoPlayer.class);
    private final String source;
    private final Path cleanupFile;
    private final boolean loop;
    private final boolean dropWhenFull;
    private final int targetWidth;
    private final int targetHeight;
    private final double maxFps;
    private final int networkTimeoutMs;
    private final int networkBufferKb;
    private final boolean networkReconnect;
    private final Map<String, String> networkOptions;
    private final ExecutorService executor;
    private final BlockingQueue<VideoFrame> frames;

    private volatile boolean closed = false;
    private volatile long mediaDurationMs = -1;

    private VideoPlayer(String source, Path cleanupFile, boolean loop, boolean dropWhenFull, int targetWidth, int targetHeight, double maxFps, int queueSize, int networkTimeoutMs, int networkBufferKb, boolean networkReconnect, Map<String, String> networkOptions) {
        this.source = source;
        this.cleanupFile = cleanupFile;
        this.loop = loop;
        this.dropWhenFull = dropWhenFull;
        this.targetWidth = targetWidth;
        this.targetHeight = targetHeight;
        this.maxFps = maxFps;
        this.networkTimeoutMs = networkTimeoutMs;
        this.networkBufferKb = networkBufferKb;
        this.networkReconnect = networkReconnect;
        this.networkOptions = networkOptions != null ? networkOptions : Map.of();
        this.frames = new ArrayBlockingQueue<>(Math.max(1, queueSize));
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread t = new Thread(runnable, "ApricityUI-VideoDecoder");
            t.setDaemon(true);
            return t;
        });
        this.executor.execute(this::decodeLoop);
    }

    public static VideoPlayer open(String resolvedPath, boolean loop) {
        return open(resolvedPath, loop, true, -1, -1, 0, 8, 15000, 512, true, Map.of());
    }

    public static VideoPlayer open(String resolvedPath, boolean loop, int targetWidth, int targetHeight, double maxFps, int queueSize) {
        return open(resolvedPath, loop, true, targetWidth, targetHeight, maxFps, queueSize, 15000, 512, true, Map.of());
    }

    public static VideoPlayer open(String resolvedPath, boolean loop, boolean dropWhenFull, int targetWidth, int targetHeight, double maxFps, int queueSize, int networkTimeoutMs, int networkBufferKb, boolean networkReconnect) {
        return open(resolvedPath, loop, dropWhenFull, targetWidth, targetHeight, maxFps, queueSize, networkTimeoutMs, networkBufferKb, networkReconnect, Map.of());
    }

    public static VideoPlayer open(String resolvedPath, boolean loop, boolean dropWhenFull, int targetWidth, int targetHeight, double maxFps, int queueSize, int networkTimeoutMs, int networkBufferKb, boolean networkReconnect, Map<String, String> networkOptions) {
        if (!FFmpegRuntimeBootstrap.ensureReady()) {
            LOGGER.warn("VideoPlayer open aborted, runtime not ready, source={}, reason={}", resolvedPath, FFmpegRuntimeBootstrap.getInitErrorMessage());
            return null;
        }
        SourceHandle handle = prepareSource(resolvedPath);
        if (handle == null || handle.source == null || handle.source.isBlank()) {
            LOGGER.warn("VideoPlayer open failed to prepare source={}", resolvedPath);
            return null;
        }
        return new VideoPlayer(handle.source, handle.cleanupFile, loop, dropWhenFull, targetWidth, targetHeight, maxFps, queueSize, networkTimeoutMs, networkBufferKb, networkReconnect, networkOptions);
    }

    public VideoFrame pollFrame() {
        return frames.poll();
    }

    private static boolean isRemotePath(String path) {
        if (path == null) return false;
        String v = path.trim().toLowerCase();
        return v.contains("://")
                || v.contains("rtsp:")
                || v.contains("rtmp:")
                || v.contains("mms:");
    }

    /**
     * Estimated media duration in milliseconds, or -1 if unknown.
     */
    public long getDurationMs() {
        return mediaDurationMs;
    }

    @Override
    public void close() {
        closed = true;
        executor.shutdownNow();
        // Release queued frames ASAP so decoder-side pools can return before decoder closes.
        drainAndCloseFrames();
        try {
            executor.awaitTermination(200, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        drainAndCloseFrames();
        try {
            if (cleanupFile != null) {
                Files.deleteIfExists(cleanupFile);
            }
        } catch (Exception e) {
            LOGGER.warn("Cleanup video temp file failed for source={}", source, e);
        }
    }

    private void decodeLoop() {
        try (IVideoDecoder decoder = FFmpegRuntimeBootstrap.createVideoDecoder(source, targetWidth, targetHeight, maxFps, networkTimeoutMs, networkBufferKb, networkReconnect, networkOptions)) {
            mediaDurationMs = decoder.getDurationMs();
            while (!closed) {
                VideoFrame frame = decoder.readNextFrame();
                if (frame == null) {
                    if (closed) break;
                    if (!loop) {
                        sleep(10);
                        continue;
                    }
                    decoder.rewind();
                    continue;
                }

                if (!frames.offer(frame)) {
                    if (dropWhenFull) {
                        VideoFrame dropped = frames.poll();
                        if (dropped != null) {
                            try {
                                dropped.close();
                            } catch (Exception ignored) {
                            }
                        }
                        if (!frames.offer(frame)) {
                            try {
                                frame.close();
                            } catch (Exception ignored) {
                            }
                        }
                    } else {
                        // If we must preserve frames, wait a bit then give up to avoid blocking shutdown.
                        boolean queued = false;
                        for (int i = 0; i < 10 && !closed; i++) {
                            sleep(2);
                            if (frames.offer(frame)) {
                                queued = true;
                                break;
                            }
                        }
                        if (!queued) {
                            try {
                                frame.close();
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }
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

    private void drainAndCloseFrames() {
        VideoFrame frame;
        while ((frame = frames.poll()) != null) {
            try {
                frame.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static String sanitize(String value) {
        if (value == null) return "video";
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private record SourceHandle(String source, Path cleanupFile) {
    }
}
