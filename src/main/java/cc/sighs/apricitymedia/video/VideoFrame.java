package cc.sighs.apricitymedia.video;

import java.nio.ByteBuffer;

/**
 * A decoded video frame in RGBA byte order.
 *
 * <p>Pixels are stored as {@code width * height * 4} bytes (R, G, B, A).
 * The backing buffer is typically direct/native and may be pooled; callers should
 * invoke {@link #close()} when the frame is no longer needed.
 */
public final class VideoFrame implements AutoCloseable {
    private final ByteBuffer pixelsRgba;
    private final int width;
    private final int height;
    private final long ptsMs;
    private final int durationMs;
    private final Runnable releaser;
    private boolean closed = false;

    public VideoFrame(ByteBuffer pixelsRgba, int width, int height, long ptsMs, int durationMs, Runnable releaser) {
        this.pixelsRgba = pixelsRgba;
        this.width = width;
        this.height = height;
        this.ptsMs = ptsMs;
        this.durationMs = durationMs;
        this.releaser = releaser;
    }

    public ByteBuffer pixelsRgba() {
        return pixelsRgba;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public long ptsMs() {
        return ptsMs;
    }

    public int durationMs() {
        return durationMs;
    }

    public int pixelBytes() {
        return Math.max(0, width) * Math.max(0, height) * 4;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (releaser != null) {
            try {
                releaser.run();
            } catch (Exception ignored) {
            }
        }
    }
}
