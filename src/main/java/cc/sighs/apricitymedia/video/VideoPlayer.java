package cc.sighs.apricitymedia.video;

import cc.sighs.apricitymedia.FFmpegRuntimeBootstrap;
import cc.sighs.apricitymedia.util.BilibiliLiveUtil;
import com.sighs.apricityui.instance.Loader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
    private final boolean realtimeSource;
    private final ExecutorService executor;
    private final BlockingQueue<VideoFrame> frames;

    private volatile boolean closed = false;
    private volatile long mediaDurationMs = -1;

    private VideoPlayer(String source, Path cleanupFile, boolean loop, boolean dropWhenFull, boolean realtimeSource, int targetWidth, int targetHeight, double maxFps, int queueSize, int networkTimeoutMs, int networkBufferKb, boolean networkReconnect, Map<String, String> networkOptions) {
        this.source = source;
        this.cleanupFile = cleanupFile;
        this.loop = loop;
        this.dropWhenFull = dropWhenFull;
        this.realtimeSource = realtimeSource;
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
        boolean realtimeSource = isRemotePath(handle.source);
        return new VideoPlayer(handle.source, handle.cleanupFile, loop, dropWhenFull, realtimeSource, targetWidth, targetHeight, maxFps, queueSize, networkTimeoutMs, networkBufferKb, networkReconnect, networkOptions);
    }

    public VideoFrame pollFrame() {
        return frames.poll();
    }

    private static List<String> resolveSourceCandidates(String rawSource) {
        if (rawSource == null || rawSource.isBlank()) return List.of();
        if (!isRemotePath(rawSource)) return List.of(rawSource);
        String lower = rawSource.toLowerCase();
        if (lower.contains("bilivideo.com/live-bvc/")) {
            List<String> chain = BilibiliLiveUtil.getFallbackChain(rawSource);
            if (!chain.isEmpty()) return chain;
        }
        return List.of(rawSource);
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

    public boolean isRealtimeSource() {
        return realtimeSource;
    }

    private void decodeLoop() {
        IVideoDecoder created = openDecoderWithFallback();
        if (created == null) {
            LOGGER.error("Video decoder creation returned null for source={}", source);
            return;
        }
        try (IVideoDecoder decoder = created) {
            if (decoder == null) {
                LOGGER.error("Video decoder creation returned null for source={}", source);
                return;
            }
            LOGGER.info("[ApricityMediaDiag] decoder start source={}, realtime={}, loop={}, dropWhenFull={}, queueCap={}, maxFps={}, netTimeoutMs={}, netBufKb={}, reconnect={}",
                    source, realtimeSource, loop, dropWhenFull, frames.remainingCapacity() + frames.size(), maxFps, networkTimeoutMs, networkBufferKb, networkReconnect);
            mediaDurationMs = decoder.getDurationMs();
            int emptyReads = 0;
            boolean hasDecodedFrame = false;
            long lastPts = Long.MIN_VALUE;
            long decodedFrames = 0;
            long nullReads = 0;
            long queueFull = 0;
            long droppedFrames = 0;
            long discardedFrames = 0;
            long statAt = System.currentTimeMillis();
            long lastDecodedSnapshot = 0;
            long lastNullSnapshot = 0;
            long lastQueueFullSnapshot = 0;
            long lastDroppedSnapshot = 0;
            long lastDiscardedSnapshot = 0;
            long decodeClockStartMs = -1;
            long decodeBasePtsMs = Long.MIN_VALUE;
            while (!closed) {
                int cap = frames.size() + frames.remainingCapacity();
                int queued = frames.size();
                if (realtimeSource) {
                    // Real-time source should not decode infinitely ahead.
                    // Keep a small headroom so render thread can pull frames steadily.
                    int nearFull = Math.max(1, cap - 2);
                    if (queued >= nearFull) {
                        sleep(3);
                        continue;
                    }
                } else {
                    if (queued >= Math.max(1, cap - 1)) {
                        sleep(2);
                        continue;
                    }
                }
                VideoFrame frame = decoder.readNextFrame();
                if (frame == null) {
                    if (closed) break;
                    emptyReads++;
                    nullReads++;
                    if (emptyReads == 12 || emptyReads == 60 || emptyReads % 300 == 0) {
                        LOGGER.warn("[ApricityMediaDiag] decoder null streak source={}, streak={}, queued={}, lastPts={}",
                                source, emptyReads, frames.size(), lastPts);
                    }
                    // Null commonly means temporary starvation (EAGAIN / pool exhaustion), not EOF.
                    // Only rewind when we are actually near media end.
                    if (loop && hasDecodedFrame && emptyReads >= 12 && mediaDurationMs > 0) {
                        long tailMs = mediaDurationMs - lastPts;
                        if (tailMs <= 500) {
                            decoder.rewind();
                            emptyReads = 0;
                            hasDecodedFrame = false;
                            lastPts = Long.MIN_VALUE;
                        }
                    }
                    sleep(8);
                    continue;
                }
                emptyReads = 0;
                hasDecodedFrame = true;
                decodedFrames++;
                lastPts = frame.ptsMs();
                if (realtimeSource) {
                    if (decodeClockStartMs < 0 || decodeBasePtsMs == Long.MIN_VALUE) {
                        decodeClockStartMs = System.currentTimeMillis();
                        decodeBasePtsMs = lastPts;
                    } else {
                        long expectedWallMs = decodeClockStartMs + Math.max(0, lastPts - decodeBasePtsMs);
                        long aheadMs = expectedWallMs - System.currentTimeMillis();
                        if (aheadMs > 24) {
                            sleep(Math.min(8, aheadMs - 16));
                        }
                    }
                }

                if (!frames.offer(frame)) {
                    queueFull++;
                    if (dropWhenFull) {
                        if (realtimeSource) {
                            VideoFrame dropped = frames.poll();
                            if (dropped != null) {
                                droppedFrames++;
                                try {
                                    dropped.close();
                                } catch (Exception ignored) {
                                }
                            }
                            if (!frames.offer(frame)) {
                                discardedFrames++;
                                try {
                                    frame.close();
                                } catch (Exception ignored) {
                                }
                            }
                        } else {
                            // For local/VOD playback, preserve timeline continuity and drop the newest frame.
                            droppedFrames++;
                            try {
                                frame.close();
                            } catch (Exception ignored) {
                            }
                        }
                    } else {
                        // If we must preserve frames, wait a bit then give up to avoid blocking shutdown.
                        boolean enqueued = false;
                        for (int i = 0; i < 10 && !closed; i++) {
                            sleep(2);
                            if (frames.offer(frame)) {
                                enqueued = true;
                                break;
                            }
                        }
                        if (!enqueued) {
                            discardedFrames++;
                            try {
                                frame.close();
                            } catch (Exception ignored) {
                            }
                        }
                    }
                    if (queueFull == 1 || queueFull % 200 == 0) {
                        LOGGER.warn("[ApricityMediaDiag] decoder queue full source={}, total={}, droppedTotal={}, discardedTotal={}, queued={}",
                                source, queueFull, droppedFrames, discardedFrames, frames.size());
                    }
                }

                long now = System.currentTimeMillis();
                if (now - statAt >= 1000) {
                    long dDecoded = decodedFrames - lastDecodedSnapshot;
                    long dNull = nullReads - lastNullSnapshot;
                    long dQueueFull = queueFull - lastQueueFullSnapshot;
                    long dDropped = droppedFrames - lastDroppedSnapshot;
                    long dDiscarded = discardedFrames - lastDiscardedSnapshot;
                    int capNow = frames.size() + frames.remainingCapacity();
                    LOGGER.info("[ApricityMediaDiag] decoder stats source={}, queued={}/{}, +decoded={}, +null={}, +queueFull={}, +dropped={}, +discarded={}, lastPts={}",
                            source, frames.size(), capNow, dDecoded, dNull, dQueueFull, dDropped, dDiscarded, lastPts);
                    statAt = now;
                    lastDecodedSnapshot = decodedFrames;
                    lastNullSnapshot = nullReads;
                    lastQueueFullSnapshot = queueFull;
                    lastDroppedSnapshot = droppedFrames;
                    lastDiscardedSnapshot = discardedFrames;
                }
            }
        } catch (Exception e) {
            LOGGER.error("Video decode loop failed for source={}", source, e);
        }
    }

    private IVideoDecoder openDecoderWithFallback() {
        List<String> candidates = resolveSourceCandidates(source);
        for (int i = 0; i < candidates.size(); i++) {
            String candidate = candidates.get(i);
            try {
                IVideoDecoder decoder = FFmpegRuntimeBootstrap.createVideoDecoder(
                        candidate, targetWidth, targetHeight, maxFps,
                        networkTimeoutMs, networkBufferKb, networkReconnect, networkOptions
                );
                if (decoder != null) {
                    if (i > 0) {
                        LOGGER.warn("Video source unavailable, switched to fallback #{}: {} -> {}",
                                i + 1, source, candidate);
                    }
                    return decoder;
                }
            } catch (Exception e) {
                LOGGER.warn("Video open failed for candidate #{}: {}", i + 1, candidate, e);
            }
            if (i < candidates.size() - 1) {
                LOGGER.warn("Video candidate unavailable, trying next #{} for source={}", i + 2, source);
            }
        }
        return null;
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
