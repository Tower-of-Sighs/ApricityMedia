package cc.sighs.apricitymedia.element;

import cc.sighs.apricitymedia.audio.AudioPlayback;
import cc.sighs.apricitymedia.video.VideoFrame;
import cc.sighs.apricitymedia.video.VideoPlayer;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Drawer;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.instance.Loader;
import com.sighs.apricityui.registry.annotation.ElementRegister;
import com.sighs.apricityui.render.Base;
import com.sighs.apricityui.render.ImageDrawer;
import com.sighs.apricityui.render.Rect;
import com.sighs.apricityui.style.Position;
import com.sighs.apricityui.style.Size;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@ElementRegister(Video.TAG_NAME)
public class Video extends Element {
    public static final String TAG_NAME = "VIDEO";

    private static final String ATTR_SRC = "src";
    private static final String ATTR_AUTOPLAY = "autoplay";
    private static final String ATTR_LOOP = "loop";
    private static final String ATTR_PAUSED = "paused";
    private static final String ATTR_BLUR = "blur";
    private static final String ATTR_PRELOAD = "preload";
    private static final String ATTR_POSTER = "poster";
    private static final String ATTR_DECODE_WIDTH = "decode-width";
    private static final String ATTR_DECODE_HEIGHT = "decode-height";
    private static final String ATTR_MAX_FPS = "max-fps";
    private static final String ATTR_DROP_FRAMES = "drop-frames";
    private static final String ATTR_MUTED = "muted";
    private static final String ATTR_VOLUME = "volume";
    private static final String ATTR_NETWORK_TIMEOUT_MS = "network-timeout-ms";
    private static final String ATTR_NETWORK_BUFFER_KB = "network-buffer-kb";
    private static final String ATTR_NETWORK_RECONNECT = "network-reconnect";

    private String resolvedSrc = "";
    private String resolvedPoster = "";
    private String preload = "";
    private boolean autoplay = false;
    private boolean loop = false;
    private boolean paused = false;
    private boolean muted = false;
    private double volume = 1.0;
    private int decodeWidth = -1;
    private int decodeHeight = -1;
    private double maxFps = 0;
    private boolean dropFrames = true;
    private int networkTimeoutMs = 15000;
    private int networkBufferKb = 512;
    private boolean networkReconnect = true;

    private VideoPlayer player;
    private AudioPlayback audio;
    private boolean audioOpening = false;
    private VideoFrame currentFrame;
    private VideoFrame pendingFrame;
    private long pendingDisplayAtMs = 0;
    private long playbackStartMs = -1;
    private long basePtsMs = 0;
    private long pauseAccumulatedMs = 0;
    private long pausedAtMs = -1;
    private boolean lastPaused = false;

    private NativeImage nativeImage;
    private DynamicTexture dynamicTexture;
    private ResourceLocation textureLocation;
    private int frameW = 0;
    private int frameH = 0;

    private final String textureId = UUID.randomUUID().toString().replace("-", "");

    public Video(Document document) {
        super(document, TAG_NAME);
    }

    @Override
    protected void onInitFromDom(Element origin) {
        syncFromAttributes();
        if (shouldPreload()) {
            ensurePlayer();
        }
    }

    @Override
    public void setAttribute(String name, String value) {
        String key = normalizeAttr(name);
        String beforeSrc = getAttribute(ATTR_SRC);
        super.setAttribute(name, value);
        if (Objects.equals(key, ATTR_SRC) && !Objects.equals(beforeSrc, getAttribute(ATTR_SRC))) {
            syncFromAttributes();
            restartPlayer();
            return;
        }
        if (shouldRestartForKey(key)) {
            syncFromAttributes();
            restartPlayer();
            return;
        }
        if (Objects.equals(key, ATTR_AUTOPLAY) || Objects.equals(key, ATTR_LOOP) || Objects.equals(key, ATTR_PAUSED)) {
            syncFromAttributes();
            if (shouldPreload()) {
                ensurePlayer();
            }
        }
    }

    @Override
    public void removeAttribute(String name) {
        String key = normalizeAttr(name);
        String beforeSrc = getAttribute(ATTR_SRC);
        super.removeAttribute(name);
        if (Objects.equals(key, ATTR_SRC) && !Objects.equals(beforeSrc, getAttribute(ATTR_SRC))) {
            syncFromAttributes();
            restartPlayer();
            return;
        }
        if (shouldRestartForKey(key)) {
            syncFromAttributes();
            restartPlayer();
            return;
        }
        if (Objects.equals(key, ATTR_AUTOPLAY) || Objects.equals(key, ATTR_LOOP) || Objects.equals(key, ATTR_PAUSED)) {
            syncFromAttributes();
            if (shouldPreload()) {
                ensurePlayer();
            }
        }
    }

    @Override
    public void tick() {
        super.tick();

        if (resolvedSrc.isEmpty()) return;
        if (player == null && shouldPreload()) {
            ensurePlayer();
        }
        if (audio == null && shouldPreload() && !muted) {
            ensureAudio();
        }
        applyAudioRuntime();
    }

    @Override
    public void remove() {
        closePlayer();
        super.remove();
    }

    @Override
    public void drawPhase(PoseStack poseStack, Base.RenderPhase phase) {
        Rect rectRenderer = Rect.of(this);
        switch (phase) {
            case SHADOW -> rectRenderer.drawShadow(poseStack);
            case BODY -> {
                rectRenderer.drawBody(poseStack);
                drawVideo(poseStack, rectRenderer);
            }
            case BORDER -> rectRenderer.drawBorder(poseStack);
        }
    }

    private void drawVideo(PoseStack poseStack, Rect rect) {
        long now = System.currentTimeMillis();
        syncPauseClock(now);

        boolean playing = autoplay && !paused;
        if (playing && player != null) {
            pumpFramesForTime(now);
        }

        Position position = rect.getBodyRectPosition();
        Size size = rect.getBodyRectSize();

        float x = (float) position.x;
        float y = (float) position.y;
        float width = (float) size.width();
        float height = (float) size.height();

        if (textureLocation == null) {
            if (resolvedPoster.isEmpty()) return;
            ImageDrawer.draw(poseStack, resolvedPoster, (int) x, (int) y, (int) width, (int) height, false);
            return;
        }

        if (width == 0 && height > 0 && frameW > 0 && frameH > 0) {
            width = (float) (1d * height / frameH * frameW);
        }
        if (height == 0 && width > 0 && frameW > 0 && frameH > 0) {
            height = (float) (1d * width / frameW * frameH);
        }

        boolean blur = "true".equalsIgnoreCase(getAttribute(ATTR_BLUR));
        ImageDrawer.draw(poseStack, textureLocation, x, y, width, height, blur);
    }

    private void syncFromAttributes() {
        String rawSrc = getAttribute(ATTR_SRC);
        if (rawSrc == null || rawSrc.isBlank()) {
            resolvedSrc = "";
        } else {
            String contextPath = document != null ? document.getPath() : "";
            resolvedSrc = Loader.resolve(contextPath, rawSrc);
        }

        String rawPoster = getAttribute(ATTR_POSTER);
        if (rawPoster == null || rawPoster.isBlank()) {
            resolvedPoster = "";
        } else {
            String contextPath = document != null ? document.getPath() : "";
            resolvedPoster = Loader.resolve(contextPath, rawPoster);
        }

        preload = normalizePreload(getAttribute(ATTR_PRELOAD));
        autoplay = isTruthyAttr(ATTR_AUTOPLAY);
        loop = isTruthyAttr(ATTR_LOOP);
        paused = isTruthyAttr(ATTR_PAUSED);
        muted = isTruthyAttr(ATTR_MUTED);
        volume = clamp01(parseDouble(getAttribute(ATTR_VOLUME), 1.0));
        decodeWidth = parseInt(getAttribute(ATTR_DECODE_WIDTH), -1);
        decodeHeight = parseInt(getAttribute(ATTR_DECODE_HEIGHT), -1);
        maxFps = Math.max(0, parseDouble(getAttribute(ATTR_MAX_FPS), 0));
        dropFrames = parseBoolean(getAttribute(ATTR_DROP_FRAMES), true);
        networkTimeoutMs = Math.max(1000, parseInt(getAttribute(ATTR_NETWORK_TIMEOUT_MS), 15000));
        networkBufferKb = Math.max(64, parseInt(getAttribute(ATTR_NETWORK_BUFFER_KB), 512));
        networkReconnect = parseBoolean(getAttribute(ATTR_NETWORK_RECONNECT), true);
    }

    private void ensurePlayer() {
        if (player != null) return;
        if (resolvedSrc.isEmpty()) return;
        player = VideoPlayer.open(
                resolvedSrc,
                loop,
                decodeWidth,
                decodeHeight,
                maxFps,
                dropFrames ? 16 : 64,
                networkTimeoutMs,
                networkBufferKb,
                networkReconnect
        );
        ensureAudio();
    }

    private void restartPlayer() {
        closePlayer();
        ensurePlayer();
    }

    private void closePlayer() {
        VideoPlayer oldPlayer = player;
        player = null;
        if (oldPlayer != null) {
            oldPlayer.close();
        }
        closeAudio();
        currentFrame = null;
        pendingFrame = null;
        pendingDisplayAtMs = 0;
        playbackStartMs = -1;
        basePtsMs = 0;
        pauseAccumulatedMs = 0;
        pausedAtMs = -1;
        lastPaused = false;
        destroyTexture();
    }

    private void applyFrame(VideoFrame frame) {
        if (frame == null) return;
        ensureTexture(frame.width(), frame.height());
        if (nativeImage == null || dynamicTexture == null) return;

        int[] pixels = frame.pixelsAbgr();
        int w = frame.width();
        int h = frame.height();

        RenderSystem.recordRenderCall(() -> {
            if (nativeImage == null || dynamicTexture == null) return;
            if (nativeImage.getWidth() != w || nativeImage.getHeight() != h) return;
            int idx = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    nativeImage.setPixelRGBA(x, y, pixels[idx++]);
                }
            }
            dynamicTexture.upload();
        });
    }

    private void ensureTexture(int w, int h) {
        if (w <= 0 || h <= 0) return;
        if (nativeImage != null && nativeImage.getWidth() == w && nativeImage.getHeight() == h) return;

        destroyTexture();

        frameW = w;
        frameH = h;

        nativeImage = new NativeImage(NativeImage.Format.RGBA, w, h, true);
        dynamicTexture = new DynamicTexture(nativeImage);
        dynamicTexture.setFilter(true, false);

        textureLocation = new ResourceLocation("apricityui", "video/" + textureId);
        Minecraft.getInstance().getTextureManager().register(textureLocation, dynamicTexture);
    }

    private void destroyTexture() {
        DynamicTexture oldTexture = dynamicTexture;
        dynamicTexture = null;
        nativeImage = null;
        textureLocation = null;
        frameW = 0;
        frameH = 0;

        if (oldTexture != null) {
            RenderSystem.recordRenderCall(oldTexture::close);
        }
    }

    private boolean isTruthyAttr(String name) {
        String value = getAttribute(name);
        if (value == null) return false;
        if (value.isBlank()) return hasAttribute(name);
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return "true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized) || name.equals(normalized);
    }

    private boolean shouldPreload() {
        if (autoplay && !paused) return true;
        return !preload.equals("none");
    }

    private boolean shouldRestartForKey(String key) {
        return Objects.equals(key, ATTR_DECODE_WIDTH)
                || Objects.equals(key, ATTR_DECODE_HEIGHT)
                || Objects.equals(key, ATTR_MAX_FPS)
                || Objects.equals(key, ATTR_DROP_FRAMES);
    }

    private void syncPauseClock(long nowMs) {
        if (paused == lastPaused) return;
        if (paused) {
            pausedAtMs = nowMs;
        } else {
            if (pausedAtMs > 0) {
                pauseAccumulatedMs += Math.max(0, nowMs - pausedAtMs);
            }
            pausedAtMs = -1;
        }
        lastPaused = paused;
    }

    private void pumpFramesForTime(long nowMs) {
        long effectiveNow = nowMs - pauseAccumulatedMs;
        VideoFrame toPresent = null;
        boolean hasPresentedFrame = currentFrame != null;

        while (true) {
            VideoFrame next = pendingFrame != null ? pendingFrame : player.pollFrame();
            if (next == null) break;

            if (playbackStartMs < 0) {
                playbackStartMs = effectiveNow;
                basePtsMs = next.ptsMs();
            }

            long displayAt = playbackStartMs + Math.max(0, next.ptsMs() - basePtsMs);
            if (displayAt > effectiveNow) {
                pendingFrame = next;
                pendingDisplayAtMs = displayAt;
                break;
            }

            pendingFrame = null;
            pendingDisplayAtMs = 0;
            toPresent = next;
            if (!dropFrames || !hasPresentedFrame) break;
        }

        if (pendingFrame != null && pendingDisplayAtMs <= effectiveNow) {
            toPresent = pendingFrame;
            pendingFrame = null;
            pendingDisplayAtMs = 0;
        }

        if (toPresent != null) {
            applyFrame(toPresent);
            currentFrame = toPresent;
            document.markDirty(this, Drawer.REPAINT);
        }
    }

    private void ensureAudio() {
        if (audio != null) return;
        if (audioOpening) return;
        if (resolvedSrc.isEmpty()) return;
        audioOpening = true;
        try {
            audio = AudioPlayback.open(resolvedSrc, loop, muted, volume, networkTimeoutMs, networkBufferKb, networkReconnect);
            if (audio != null) {
                audio.setMuted(muted);
                audio.setVolume(volume);
                audio.setPaused(paused || !autoplay);
            }
        } finally {
            audioOpening = false;
        }
    }

    private void applyAudioRuntime() {
        if (audio == null) return;
        audio.setMuted(muted);
        audio.setVolume(volume);
        audio.setPaused(paused || !autoplay);
    }

    private void closeAudio() {
        AudioPlayback old = audio;
        audio = null;
        if (old != null) {
            old.close();
        }
    }

    public void closeMedia() {
        closePlayer();
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static double parseDouble(String value, double fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static boolean parseBoolean(String value, boolean fallback) {
        if (value == null) return fallback;
        if (value.isBlank()) return true;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return "true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized) || "on".equals(normalized);
    }

    private static double clamp01(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private static String normalizePreload(String value) {
        if (value == null || value.isBlank()) return "auto";
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "none", "metadata", "auto" -> normalized;
            default -> "auto";
        };
    }

    private static String normalizeAttr(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }
}
