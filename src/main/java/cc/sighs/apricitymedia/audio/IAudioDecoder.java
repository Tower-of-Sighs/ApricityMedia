package cc.sighs.apricitymedia.audio;

public interface IAudioDecoder extends AutoCloseable {
    /**
     * Decode next PCM chunk into {@code out}.
     *
     * <p>Returns:
     * <ul>
     *   <li>{@code > 0}: bytes written</li>
     *   <li>{@code 0}: no output available yet (try again)</li>
     *   <li>{@code -1}: end of stream (decoder drained)</li>
     * </ul>
     */
    int readNextPcmChunk(byte[] out, int offset, int length);

    default int readNextPcmChunk(byte[] out) {
        return readNextPcmChunk(out, 0, out == null ? 0 : out.length);
    }

    void rewind();

    int outSampleRate();

    int outChannels();

    /**
     * Estimated media duration in milliseconds, or -1 if unknown.
     */
    default long getDurationMs() {
        return -1;
    }
}
