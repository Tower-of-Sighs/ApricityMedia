package cc.sighs.apricitymedia.element;

import cc.sighs.apricitymedia.audio.AudioPlayback;
import cc.sighs.apricitymedia.hls.HlsMasterPlaylist;
import cc.sighs.apricitymedia.util.MediaUtil;
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
import org.lwjgl.system.MemoryUtil;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
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
    private static final String ATTR_HLS_POLICY = "hls-policy";

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
    private static volatile Field NATIVE_IMAGE_PIXELS_FIELD;
    private final Map<String, String> networkOptions = new HashMap<>();

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
    private String hlsPolicy = "auto";
    // Media state for web-compatible API and events
    private double mediaDurationSecs = -1;
    private double mediaCurrentTimeSecs = 0;
    private boolean mediaEnded = false;
    private int readyState = 0;
    private int networkState = 0;
    private boolean mediaHasFiredLoadedMetadata = false;
    private boolean mediaHasFiredCanPlay = false;
    private long mediaLastTimeUpdateMs = 0;
    private boolean mediaPrevPaused = false;
    private boolean mediaPrevMuted = false;

    private NativeImage nativeImage;
    private DynamicTexture dynamicTexture;
    private ResourceLocation textureLocation;
    private int frameW = 0;
    private int frameH = 0;
    private double mediaPrevVolume = 1.0;

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

    private static void closeFrame(VideoFrame frame) {
        if (frame == null) return;
        try {
            frame.close();
        } catch (Exception ignored) {
        }
    }

    private static long nativeImagePixelsAddress(NativeImage image) {
        if (image == null) return 0L;
        try {
            Field field = NATIVE_IMAGE_PIXELS_FIELD;
            if (field == null) {
                synchronized (Video.class) {
                    field = NATIVE_IMAGE_PIXELS_FIELD;
                    if (field == null) {
                        field = NativeImage.class.getDeclaredField("pixels");
                        field.setAccessible(true);
                        NATIVE_IMAGE_PIXELS_FIELD = field;
                    }
                }
            }
            return field.getLong(image);
        } catch (Exception ignored) {
            return 0L;
        }
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

    private void restartPlayer() {
        closePlayer();
        ensurePlayer();
    }

    private static String normalizeHlsPolicy(String value) {
        return MediaUtil.normalizeHlsPolicy(value);
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

    private static boolean isRemoteUrl(String url) {
        return MediaUtil.isRemoteUrl(url);
    }

    private static HlsMasterPlaylist.Policy parsePolicy(String policy, boolean isVideo) {
        return MediaUtil.parsePolicy(policy, isVideo);
    }

    private static HlsMasterPlaylist.Policy parseHlsPolicy(String policy) {
        return MediaUtil.parseHlsPolicy(policy, true);
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

    private static int parseInt(String value, int fallback) {
        return MediaUtil.parseInt(value, fallback);
    }

    private static double parseDouble(String value, double fallback) {
        return MediaUtil.parseDouble(value, fallback);
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
        if (Objects.equals(key, ATTR_AUTOPLAY) || Objects.equals(key, ATTR_LOOP) || Objects.equals(key, ATTR_PAUSED)
                || Objects.equals(key, ATTR_MUTED) || Objects.equals(key, ATTR_VOLUME)) {
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
        if (Objects.equals(key, ATTR_AUTOPLAY) || Objects.equals(key, ATTR_LOOP) || Objects.equals(key, ATTR_PAUSED)
                || Objects.equals(key, ATTR_MUTED) || Objects.equals(key, ATTR_VOLUME)) {
            syncFromAttributes();
            if (shouldPreload()) {
                ensurePlayer();
            }
        }
    }

    @Override
    public void tick() {
        super.tick();

        if (resolvedSrc.isEmpty()) {
            networkState = 0; // NETWORK_EMPTY
            return;
        }
        if (player == null && shouldPreload()) {
            ensurePlayer();
        }
        if (audio == null && shouldPreload() && !muted) {
            ensureAudio();
        }
        applyAudioRuntime();
        fireMediaEvents();
    }

    private void syncFromAttributes() {
        String rawSrc = getAttribute(ATTR_SRC);
        if (rawSrc == null || rawSrc.isBlank()) {
            resolvedSrc = "";
        } else if (isRemoteUrl(rawSrc)) {
            resolvedSrc = rawSrc.trim();
        } else {
            String contextPath = document != null ? document.getPath() : "";
            resolvedSrc = Loader.resolve(contextPath, rawSrc);
        }

        String rawPoster = getAttribute(ATTR_POSTER);
        if (rawPoster == null || rawPoster.isBlank()) {
            resolvedPoster = "";
        } else if (isRemoteUrl(rawPoster)) {
            resolvedPoster = rawPoster.trim();
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
        hlsPolicy = normalizeHlsPolicy(getAttribute(ATTR_HLS_POLICY));
    }

    private void ensurePlayer() {
        if (player != null) return;
        if (resolvedSrc.isEmpty()) return;
        String selected = resolveHlsForVideo(resolvedSrc);
        player = VideoPlayer.open(
                selected,
                loop,
                dropFrames,
                decodeWidth,
                decodeHeight,
                maxFps,
                dropFrames ? 16 : 64,
                networkTimeoutMs,
                networkBufferKb,
                networkReconnect,
                networkOptions
        );
        ensureAudio();
    }

    private void closePlayer() {
        VideoPlayer oldPlayer = player;
        player = null;
        closeFrame(currentFrame);
        closeFrame(pendingFrame);
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
        if (nativeImage == null || dynamicTexture == null) {
            closeFrame(frame);
            return;
        }

        int w = frame.width();
        int h = frame.height();
        int bytes = frame.pixelBytes();
        ByteBuffer pixels = frame.pixelsRgba();

        if (RenderSystem.isOnRenderThreadOrInit()) {
            uploadFrame(frame, pixels, w, h, bytes);
        } else {
            RenderSystem.recordRenderCall(() -> uploadFrame(frame, pixels, w, h, bytes));
        }
    }

    private void uploadFrame(VideoFrame frame, ByteBuffer pixels, int w, int h, int bytes) {
        if (nativeImage == null || dynamicTexture == null) {
            closeFrame(frame);
            return;
        }
        if (nativeImage.getWidth() != w || nativeImage.getHeight() != h) {
            closeFrame(frame);
            return;
        }
        if (pixels == null || !pixels.isDirect()) {
            closeFrame(frame);
            return;
        }
        long dst = nativeImagePixelsAddress(nativeImage);
        if (dst == 0L) {
            closeFrame(frame);
            return;
        }

        ByteBuffer src = pixels.duplicate();
        src.clear();
        MemoryUtil.memCopy(MemoryUtil.memAddress(src), dst, bytes);
        dynamicTexture.upload();
        closeFrame(frame);
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

            if (toPresent != null && dropFrames && hasPresentedFrame) {
                closeFrame(toPresent);
            }
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
            String selected = resolveHlsForVideo(resolvedSrc);
            audio = AudioPlayback.open(selected, loop, muted, volume, networkTimeoutMs, networkBufferKb, networkReconnect, networkOptions);
            if (audio != null) {
                audio.setMuted(muted);
                audio.setVolume(volume);
                audio.setPaused(paused || !autoplay);
            }
        } finally {
            audioOpening = false;
        }
    }

    public void play() {
        autoplay = true;
        paused = false;
        mediaEnded = false;
        setAttribute("autoplay", "true");
        setAttribute("paused", "false");
    }

    public void pause() {
        paused = true;
        setAttribute("paused", "true");
    }

    public void load() {
        mediaEnded = false;
        mediaDurationSecs = -1;
        mediaCurrentTimeSecs = 0;
        mediaHasFiredLoadedMetadata = false;
        mediaHasFiredCanPlay = false;
        readyState = 0;
        syncFromAttributes();
        restartPlayer();
    }

    public double getCurrentTime() {
        return mediaCurrentTimeSecs;
    }

    private static boolean parseBoolean(String value, boolean fallback) {
        return MediaUtil.parseBoolean(value, fallback);
    }

    public boolean isPaused() {
        return paused;
    }

    public boolean isEnded() {
        return mediaEnded;
    }

    public int getReadyState() {
        return readyState;
    }

    private static double clamp01(double value) {
        return MediaUtil.clamp01(value);
    }

    public String getSrc() {
        return resolvedSrc;
    }

    public double getVolume() {
        return volume;
    }

    public void setVolume(double v) {
        volume = clamp01(v);
        setAttribute("volume", String.valueOf(volume));
        if (audio != null) audio.setVolume(volume);
    }

    public boolean isMuted() {
        return muted;
    }

    public void setMuted(boolean m) {
        muted = m;
        setAttribute("muted", m ? "true" : "false");
        if (audio != null) audio.setMuted(m);
    }

    public boolean isLoop() {
        return loop;
    }

    public void setLoop(boolean l) {
        loop = l;
        setAttribute("loop", l ? "true" : "false");
    }

    public boolean isAutoplay() {
        return autoplay;
    }

    private static String normalizePreload(String value) {
        return MediaUtil.normalizePreload(value);
    }

    private static String normalizeAttr(String name) {
        return MediaUtil.normalizeAttr(name);
    }

    private boolean isTruthyAttr(String name) {
        return MediaUtil.isTruthyAttr(this, name);
    }

    private boolean shouldPreload() {
        return MediaUtil.shouldPreload(autoplay, paused, preload);
    }

    private boolean shouldRestartForKey(String key) {
        return MediaUtil.shouldRestartForKey(key);
    }

    // -1 means unknown (NaN in JS)
    public double getDuration() {
        return mediaDurationSecs;
    }

    // 0=EMPTY 1=IDLE 2=LOADING 3=NO_SOURCE
    public int getNetworkState() {
        return networkState;
    }

    private void fireMediaEvents() {
        boolean playing = autoplay && !paused;
        long now = System.currentTimeMillis();

        // Track current time from displayed frame
        if (playing && currentFrame != null) {
            mediaCurrentTimeSecs = currentFrame.ptsMs() / 1000.0;
        }

        // Update duration when we get it from the player
        if (mediaDurationSecs < 0 && player != null) {
            long durMs = player.getDurationMs();
            if (durMs > 0) {
                mediaDurationSecs = durMs / 1000.0;
                dispatchMediaEvent("durationchange");
                readyState = Math.max(readyState, 1);
                mediaHasFiredLoadedMetadata = true;
                dispatchMediaEvent("loadedmetadata");
            }
        }

        // Ready state transitions
        if (!mediaHasFiredLoadedMetadata && resolvedSrc != null && !resolvedSrc.isEmpty() && player != null) {
            if (currentFrame != null) {
                readyState = Math.max(readyState, 2);
                if (!mediaHasFiredCanPlay) {
                    mediaHasFiredCanPlay = true;
                    dispatchMediaEvent("canplay");
                }
            }
        }

        if (player != null && readyState < 1) {
            readyState = 1;
            networkState = 1; // NETWORK_IDLE
            if (!mediaHasFiredLoadedMetadata) {
                mediaHasFiredLoadedMetadata = true;
                dispatchMediaEvent("loadedmetadata");
            }
        }

        // Network state
        if (!resolvedSrc.isEmpty()) {
            if (networkState == 0) {
                networkState = 1; // NETWORK_IDLE
            }
        }

        // play / pause transition
        if (paused != mediaPrevPaused) {
            if (!paused && mediaEnded) {
                mediaEnded = false;
            }
            dispatchMediaEvent(paused ? "pause" : "play");
            mediaPrevPaused = paused;
        }

        // volumechange
        if (muted != mediaPrevMuted || Math.abs(volume - mediaPrevVolume) > 0.0001) {
            dispatchMediaEvent("volumechange");
            mediaPrevMuted = muted;
            mediaPrevVolume = volume;
        }

        // timeupdate: fire periodically during playback
        if (playing && (now - mediaLastTimeUpdateMs) >= 250) {
            dispatchMediaEvent("timeupdate");
            mediaLastTimeUpdateMs = now;
        }

        // Detect ended: player exists but no frames arriving and not paused
        if (playing && player != null && currentFrame == null && !mediaEnded) {
            // Check if we've seen frames before (media actually started)
            if (mediaLastTimeUpdateMs > 0 || mediaCurrentTimeSecs > 0.01) {
                mediaEnded = true;
                dispatchMediaEvent("ended");
            }
        }
    }

    public String hlsHighest(String masterUrl) {
        return HlsMasterPlaylist.selectVariantOrSelf(masterUrl, HlsMasterPlaylist.Policy.HIGHEST_BANDWIDTH, 8000);
    }

    public void setUserAgent(String userAgent) {
        networkOptions.put("user_agent", userAgent);
    }

    public void setHeaders(String headers) {
        networkOptions.put("headers", headers);
    }

    public String hlsHighest(String masterUrl, int timeoutMs) {
        return HlsMasterPlaylist.selectVariantOrSelf(masterUrl, HlsMasterPlaylist.Policy.HIGHEST_BANDWIDTH, timeoutMs);
    }

    public String hlsLowest(String masterUrl) {
        return HlsMasterPlaylist.selectVariantOrSelf(masterUrl, HlsMasterPlaylist.Policy.LOWEST_BANDWIDTH, 8000);
    }

    public String hlsLowest(String masterUrl, int timeoutMs) {
        return HlsMasterPlaylist.selectVariantOrSelf(masterUrl, HlsMasterPlaylist.Policy.LOWEST_BANDWIDTH, timeoutMs);
    }

    public String hlsSelect(String masterUrl, String policy) {
        return HlsMasterPlaylist.selectVariantOrSelf(masterUrl, parseHlsPolicy(policy), 8000);
    }

    public String hlsSelect(String masterUrl, String policy, int timeoutMs) {
        return HlsMasterPlaylist.selectVariantOrSelf(masterUrl, parseHlsPolicy(policy), timeoutMs);
    }

    private void dispatchMediaEvent(String type) {
        MediaUtil.dispatchMediaEvent(this, type);
    }

    public void setNetworkOption(String key, String value) {
        networkOptions.put(key, value);
    }

    public void clearNetworkOptions() {
        networkOptions.clear();
    }

    public Map<String, String> getNetworkOptions() {
        return new HashMap<>(networkOptions);
    }

    private String resolveHlsForVideo(String url) {
        return MediaUtil.resolveHls(url, hlsPolicy, networkTimeoutMs, true);
    }
}
