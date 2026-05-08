package cc.sighs.apricitymedia.audio;

import cc.sighs.apricitymedia.jni.ApricityMediaNative;

import java.util.Map;

/**
 * JNI-backed audio decoder — thin wrapper around {@link ApricityMediaNative}.
 *
 * <p>Replaces the JavaCPP-based {@code FFmpegAudioDecoder}. Outputs S16LE,
 * 48000 Hz, stereo PCM.
 */
public final class JniAudioDecoder implements IAudioDecoder {

    private final long decoderHandle;
    private final int sampleRate;
    private final int channels;
    private final long durationMs;
    private boolean closed;

    public JniAudioDecoder(String filePath, int networkTimeoutMs, int networkBufferKb,
                           boolean networkReconnect, Map<String, String> networkOptions) {
        decoderHandle = ApricityMediaNative.audioOpen(
                filePath, networkTimeoutMs, networkBufferKb, networkReconnect);
        if (decoderHandle == 0) {
            String nativeError = ApricityMediaNative.lastError();
            throw new IllegalStateException("audioOpen failed: " + filePath
                    + (nativeError == null || nativeError.isBlank() ? "" : " | " + nativeError));
        }
        sampleRate = ApricityMediaNative.audioSampleRate(decoderHandle);
        channels = ApricityMediaNative.audioChannels(decoderHandle);
        durationMs = ApricityMediaNative.audioGetDurationMs(decoderHandle);
    }

    @Override
    public int readNextPcmChunk(byte[] out, int offset, int length) {
        if (closed || out == null || length <= 0) return -2;
        return ApricityMediaNative.audioReadPcm(decoderHandle, out, offset, length);
    }

    @Override
    public void rewind() {
        ApricityMediaNative.audioRewind(decoderHandle);
    }

    @Override
    public int outSampleRate() {
        return sampleRate;
    }

    @Override
    public int outChannels() {
        return channels;
    }

    @Override
    public long getDurationMs() {
        return durationMs;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        ApricityMediaNative.audioClose(decoderHandle);
    }
}
