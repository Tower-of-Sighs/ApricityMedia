package cc.sighs.apricitymedia.video;

public record VideoFrame(int[] pixelsAbgr, int width, int height, long ptsMs, int durationMs) {
}
