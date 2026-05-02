package cc.sighs.apricitymedia.audio;

import cc.sighs.apricitymedia.FFmpegRuntimeBootstrap;
import com.sighs.apricityui.instance.Loader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.SourceDataLine;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public final class AudioPlayback implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(AudioPlayback.class);
    private final String source;
    private final Path cleanupFile;
    private final boolean loop;
    private final int networkTimeoutMs;
    private final int networkBufferKb;
    private final boolean networkReconnect;
    private final Map<String, String> networkOptions;
    private final ExecutorService executor;
    private final ByteRingBuffer pcmBuffer;
    private final CountDownLatch formatReady = new CountDownLatch(1);

    private volatile boolean closed = false;
    private volatile boolean paused = false;
    private volatile boolean muted = false;
    private volatile double volume = 1.0;
    private volatile long mediaDurationMs = -1;

    private volatile AudioFormat audioFormat;
    private SourceDataLine line;

    private AudioPlayback(String source, Path cleanupFile, boolean loop, boolean muted, double volume, int networkTimeoutMs, int networkBufferKb, boolean networkReconnect, Map<String, String> networkOptions) {
        this.source = source;
        this.cleanupFile = cleanupFile;
        this.loop = loop;
        this.muted = muted;
        this.volume = volume;
        this.networkTimeoutMs = networkTimeoutMs;
        this.networkBufferKb = networkBufferKb;
        this.networkReconnect = networkReconnect;
        this.networkOptions = networkOptions != null ? networkOptions : Map.of();
        int ringBytes = Math.max(256 * 1024, Math.max(64, networkBufferKb) * 1024);
        this.pcmBuffer = new ByteRingBuffer(ringBytes);
        this.executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread t = new Thread(runnable, "ApricityUI-AudioWorker");
            t.setDaemon(true);
            return t;
        });
        this.executor.execute(this::decodeLoop);
        this.executor.execute(this::playbackLoop);
    }

    public static AudioPlayback open(String resolvedPath, boolean loop, boolean muted, double volume) {
        return open(resolvedPath, loop, muted, volume, 15000, 512, true, Map.of());
    }

    public static AudioPlayback open(String resolvedPath, boolean loop, boolean muted, double volume, int networkTimeoutMs, int networkBufferKb, boolean networkReconnect) {
        return open(resolvedPath, loop, muted, volume, networkTimeoutMs, networkBufferKb, networkReconnect, Map.of());
    }

    public static AudioPlayback open(String resolvedPath, boolean loop, boolean muted, double volume, int networkTimeoutMs, int networkBufferKb, boolean networkReconnect, Map<String, String> networkOptions) {
        if (!FFmpegRuntimeBootstrap.ensureReady()) {
            LOGGER.warn("AudioPlayback open aborted, runtime not ready, source={}, reason={}", resolvedPath, FFmpegRuntimeBootstrap.getInitErrorMessage());
            return null;
        }
        SourceHandle handle = prepareSource(resolvedPath);
        if (handle == null || handle.source == null || handle.source.isBlank()) {
            LOGGER.warn("AudioPlayback open failed to prepare source={}", resolvedPath);
            return null;
        }
        return new AudioPlayback(handle.source, handle.cleanupFile, loop, muted, volume, networkTimeoutMs, networkBufferKb, networkReconnect, networkOptions);
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
        SourceDataLine current = line;
        if (current != null) {
            if (paused) current.stop();
            else current.start();
        }
    }

    public void setMuted(boolean muted) {
        this.muted = muted;
        applyVolumeControls();
    }

    private static boolean isRemotePath(String path) {
        if (path == null) return false;
        String v = path.trim().toLowerCase();
        return v.contains("://")
                || v.contains("rtsp:")
                || v.contains("rtmp:")
                || v.contains("mms:");
    }

    public void setVolume(double volume) {
        this.volume = Math.max(0, Math.min(1, volume));
        applyVolumeControls();
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
        pcmBuffer.close();
        executor.shutdownNow();
        try {
            executor.awaitTermination(200, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        closeLine();
        try {
            if (cleanupFile != null) {
                Files.deleteIfExists(cleanupFile);
            }
        } catch (Exception ignored) {
        }
    }

    private void decodeLoop() {
        byte[] decodeBuffer = new byte[32 * 1024];
        try (IAudioDecoder decoder = FFmpegRuntimeBootstrap.createAudioDecoder(source, networkTimeoutMs, networkBufferKb, networkReconnect, networkOptions)) {
            mediaDurationMs = decoder.getDurationMs();
            audioFormat = new AudioFormat(
                    decoder.outSampleRate(), 16, decoder.outChannels(), true, false);
            formatReady.countDown();

            while (!closed) {
                if (paused) {
                    sleep(5);
                    continue;
                }

                int read = decoder.readNextPcmChunk(decodeBuffer, 0, decodeBuffer.length);
                if (read < 0) {
                    if (!loop) {
                        sleep(10);
                        continue;
                    }
                    decoder.rewind();
                    pcmBuffer.clear();
                    continue;
                }
                if (read == 0) {
                    sleep(1);
                    continue;
                }

                pcmBuffer.writeBlocking(decodeBuffer, 0, read);
            }
        } catch (InterruptedException ignored) {
            formatReady.countDown();
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            formatReady.countDown();
            LOGGER.error("Audio decode loop failed for source={}", source, e);
        }
    }

    private void applyVolumeControls() {
        SourceDataLine current = line;
        if (current == null) return;
        if (!current.isControlSupported(FloatControl.Type.MASTER_GAIN)) return;
        FloatControl gain = (FloatControl) current.getControl(FloatControl.Type.MASTER_GAIN);
        double linear = muted ? 0 : Math.max(0.0001, volume);
        float db = (float) (20.0 * Math.log10(linear));
        db = Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), db));
        gain.setValue(db);
    }

    private void closeLine() {
        SourceDataLine current = line;
        line = null;
        if (current == null) return;
        try {
            current.stop();
        } catch (Exception ignored) {
        }
        try {
            current.flush();
        } catch (Exception ignored) {
        }
        try {
            current.close();
        } catch (Exception ignored) {
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
            Path tmp = Files.createTempFile("apricityui-audio-", "-" + sanitize(resolvedPath));
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
            LOGGER.warn("Prepare audio source failed for path={}", resolvedPath, e);
            return null;
        }
    }

    private void playbackLoop() {
        byte[] playBuffer = new byte[16 * 1024];
        try {
            formatReady.await();
            if (closed) return;

            AudioFormat format = audioFormat;
            if (format == null) {
                LOGGER.warn("Audio playback aborted, format not available, source={}", source);
                return;
            }

            SourceDataLine created = AudioSystem.getSourceDataLine(format);
            int frameSize = Math.max(1, format.getFrameSize());
            int suggested = Math.max(frameSize * 256, playBuffer.length * 4); // keep line-buffer reasonably large
            suggested -= (suggested % frameSize);
            created.open(format, suggested);
            created.start();
            line = created;
            applyVolumeControls();

            while (!closed) {
                if (paused) {
                    sleep(5);
                    continue;
                }

                int n = pcmBuffer.readBlocking(playBuffer, 0, playBuffer.length);
                if (n <= 0) continue;
                created.write(playBuffer, 0, n);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOGGER.error("Audio playback loop failed for source={}", source, e);
        } finally {
            closeLine();
        }
    }

    private static String sanitize(String value) {
        if (value == null) return "audio";
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private record SourceHandle(String source, Path cleanupFile) {
    }

    private static final class ByteRingBuffer {
        private final byte[] buffer;
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition notEmpty = lock.newCondition();
        private final Condition notFull = lock.newCondition();
        private int readPos = 0;
        private int writePos = 0;
        private int size = 0;
        private volatile boolean closed = false;

        private ByteRingBuffer(int capacity) {
            this.buffer = new byte[Math.max(8 * 1024, capacity)];
        }

        void clear() {
            lock.lock();
            try {
                readPos = 0;
                writePos = 0;
                size = 0;
                notFull.signalAll();
            } finally {
                lock.unlock();
            }
        }

        void close() {
            closed = true;
            lock.lock();
            try {
                notEmpty.signalAll();
                notFull.signalAll();
            } finally {
                lock.unlock();
            }
        }

        void writeBlocking(byte[] src, int offset, int length) throws InterruptedException {
            int remaining = length;
            int off = offset;
            while (remaining > 0) {
                lock.lock();
                try {
                    while (!closed && size == buffer.length) {
                        notFull.await();
                    }
                    if (closed) return;

                    int writable = buffer.length - size;
                    int chunk = Math.min(remaining, writable);
                    int first = Math.min(chunk, buffer.length - writePos);
                    System.arraycopy(src, off, buffer, writePos, first);
                    int second = chunk - first;
                    if (second > 0) {
                        System.arraycopy(src, off + first, buffer, 0, second);
                    }
                    writePos = (writePos + chunk) % buffer.length;
                    size += chunk;
                    off += chunk;
                    remaining -= chunk;
                    notEmpty.signal();
                } finally {
                    lock.unlock();
                }
            }
        }

        int readBlocking(byte[] dst, int offset, int length) throws InterruptedException {
            lock.lock();
            try {
                while (!closed && size == 0) {
                    notEmpty.await();
                }
                if (size == 0) return 0;

                int chunk = Math.min(length, size);
                int first = Math.min(chunk, buffer.length - readPos);
                System.arraycopy(buffer, readPos, dst, offset, first);
                int second = chunk - first;
                if (second > 0) {
                    System.arraycopy(buffer, 0, dst, offset + first, second);
                }
                readPos = (readPos + chunk) % buffer.length;
                size -= chunk;
                notFull.signal();
                return chunk;
            } finally {
                lock.unlock();
            }
        }
    }
}
