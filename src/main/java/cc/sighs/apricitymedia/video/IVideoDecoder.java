package cc.sighs.apricitymedia.video;

public interface IVideoDecoder extends AutoCloseable {
    VideoFrame readNextFrame();
    void rewind();

    /** Estimated media duration in milliseconds, or -1 if unknown. */
    default long getDurationMs() {
        return -1;
    }
}
