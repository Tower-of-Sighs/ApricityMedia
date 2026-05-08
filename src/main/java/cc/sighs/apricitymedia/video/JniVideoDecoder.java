package cc.sighs.apricitymedia.video;

import cc.sighs.apricitymedia.jni.ApricityMediaNative;

import java.nio.ByteBuffer;
import java.util.Map;

/**
 * JNI-backed video decoder — thin wrapper around {@link ApricityMediaNative}.
 *
 * <p>Replaces the JavaCPP-based {@code FFmpegVideoDecoder}. Uses the native
 * frame pool (8 pre-allocated RGBA buffers) for zero per-frame malloc.
 */
public final class JniVideoDecoder implements IVideoDecoder {

    private final long decoderHandle;
    private final long durationMs;
    private boolean closed;

    public JniVideoDecoder(String filePath, int targetWidth, int targetHeight,
                           double maxFps, int networkTimeoutMs, int networkBufferKb,
                           boolean networkReconnect, Map<String, String> networkOptions) {
        decoderHandle = ApricityMediaNative.videoOpen(
                filePath, targetWidth, targetHeight, maxFps,
                networkTimeoutMs, networkBufferKb, networkReconnect);
        if (decoderHandle == 0) {
            String nativeError = ApricityMediaNative.lastError();
            throw new IllegalStateException("videoOpen failed: " + filePath
                    + (nativeError == null || nativeError.isBlank() ? "" : " | " + nativeError));
        }
        durationMs = ApricityMediaNative.videoGetDurationMs(decoderHandle);
    }

    public JniVideoDecoder(String filePath) {
        this(filePath, -1, -1, 0, 15000, 512, true, Map.of());
    }

    @Override
    public VideoFrame readNextFrame() {
        long frameHandle = ApricityMediaNative.videoReadFrame(decoderHandle);
        if (frameHandle == 0) return null;

        long[] info = new long[4];
        int pixelBytes = ApricityMediaNative.videoFrameGetInfo(frameHandle, info);
        if (pixelBytes <= 0) {
            ApricityMediaNative.videoFrameRelease(frameHandle);
            return null;
        }

        ByteBuffer pixels = ApricityMediaNative.videoFrameGetPixels(frameHandle);
        if (pixels == null) {
            ApricityMediaNative.videoFrameRelease(frameHandle);
            return null;
        }

        return new VideoFrame(pixels, (int) info[0], (int) info[1], info[2], (int) info[3],
                () -> ApricityMediaNative.videoFrameRelease(frameHandle));
    }

    @Override
    public long getDurationMs() {
        return durationMs;
    }

    @Override
    public void rewind() {
        ApricityMediaNative.videoRewind(decoderHandle);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        ApricityMediaNative.videoClose(decoderHandle);
    }
}
