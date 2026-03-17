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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class AudioPlayback implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(AudioPlayback.class);
    private final String source;
    private final Path cleanupFile;
    private final boolean loop;
    private final int networkTimeoutMs;
    private final int networkBufferKb;
    private final boolean networkReconnect;
    private final ExecutorService executor;

    private volatile boolean closed = false;
    private volatile boolean paused = false;
    private volatile boolean muted = false;
    private volatile double volume = 1.0;

    private SourceDataLine line;

    private AudioPlayback(String source, Path cleanupFile, boolean loop, boolean muted, double volume, int networkTimeoutMs, int networkBufferKb, boolean networkReconnect) {
        this.source = source;
        this.cleanupFile = cleanupFile;
        this.loop = loop;
        this.muted = muted;
        this.volume = volume;
        this.networkTimeoutMs = networkTimeoutMs;
        this.networkBufferKb = networkBufferKb;
        this.networkReconnect = networkReconnect;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread t = new Thread(runnable, "ApricityUI-AudioDecoder");
            t.setDaemon(true);
            return t;
        });
        this.executor.execute(this::runLoop);
    }

    public static AudioPlayback open(String resolvedPath, boolean loop, boolean muted, double volume) {
        return open(resolvedPath, loop, muted, volume, 15000, 512, true);
    }

    public static AudioPlayback open(String resolvedPath, boolean loop, boolean muted, double volume, int networkTimeoutMs, int networkBufferKb, boolean networkReconnect) {
        if (!FFmpegRuntimeBootstrap.ensureReady()) {
            LOGGER.warn("Open audio playback skipped, FFmpeg runtime not ready: {}", FFmpegRuntimeBootstrap.getInitErrorMessage());
            return null;
        }
        SourceHandle handle = prepareSource(resolvedPath);
        if (handle == null || handle.source == null || handle.source.isBlank()) return null;
        return new AudioPlayback(handle.source, handle.cleanupFile, loop, muted, volume, networkTimeoutMs, networkBufferKb, networkReconnect);
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

    public void setVolume(double volume) {
        this.volume = Math.max(0, Math.min(1, volume));
        applyVolumeControls();
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
        closeLine();
        try {
            if (cleanupFile != null) {
                Files.deleteIfExists(cleanupFile);
            }
        } catch (Exception ignored) {
        }
    }

    private void runLoop() {
        try (FFmpegAudioDecoder decoder = new FFmpegAudioDecoder(source, networkTimeoutMs, networkBufferKb, networkReconnect)) {
            AudioFormat format = new AudioFormat(decoder.outSampleRate(), 16, decoder.outChannels(), true, false);
            SourceDataLine created = AudioSystem.getSourceDataLine(format);
            created.open(format);
            created.start();
            line = created;
            applyVolumeControls();

            while (!closed) {
                if (paused) {
                    sleep(5);
                    continue;
                }

                byte[] chunk = decoder.readNextPcmChunk();
                if (chunk == null || chunk.length == 0) {
                    if (!loop) {
                        sleep(10);
                        continue;
                    }
                    decoder.rewind();
                    continue;
                }

                if (!muted) {
                    created.write(chunk, 0, chunk.length);
                } else {
                    sleep(Math.max(1, chunk.length / 192));
                }
            }
        } catch (Exception e) {
            LOGGER.error("Audio playback loop failed for source={}", source, e);
        } finally {
            closeLine();
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
        if (value == null) return "audio";
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private record SourceHandle(String source, Path cleanupFile) {
    }
}
