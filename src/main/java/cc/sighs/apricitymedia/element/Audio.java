package cc.sighs.apricitymedia.element;

import cc.sighs.apricitymedia.audio.AudioPlayback;
import cc.sighs.apricitymedia.hls.HlsMasterPlaylist;
import cc.sighs.apricitymedia.util.MediaUtil;
import com.mojang.blaze3d.vertex.PoseStack;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.instance.Loader;
import com.sighs.apricityui.registry.annotation.ElementRegister;
import com.sighs.apricityui.render.Base;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@ElementRegister(Audio.TAG_NAME)
public class Audio extends Element {
    public static final String TAG_NAME = "AUDIO";

    private static final String ATTR_SRC = "src";
    private static final String ATTR_AUTOPLAY = "autoplay";
    private static final String ATTR_LOOP = "loop";
    private static final String ATTR_PAUSED = "paused";
    private static final String ATTR_PRELOAD = "preload";
    private static final String ATTR_MUTED = "muted";
    private static final String ATTR_VOLUME = "volume";
    private static final String ATTR_NETWORK_TIMEOUT_MS = "network-timeout-ms";
    private static final String ATTR_NETWORK_BUFFER_KB = "network-buffer-kb";
    private static final String ATTR_NETWORK_RECONNECT = "network-reconnect";
    private static final String ATTR_HLS_POLICY = "hls-policy";

    private String resolvedSrc = "";
    private String preload = "";
    private boolean autoplay = false;
    private boolean loop = false;
    private boolean paused = false;
    private boolean muted = false;
    private double volume = 1.0;
    private int networkTimeoutMs = 15000;
    private int networkBufferKb = 512;
    private boolean networkReconnect = true;
    private final Map<String, String> networkOptions = new HashMap<>();
    private String hlsPolicy = "auto";

    private AudioPlayback playback;
    private boolean lastPaused = false;
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
    private double mediaPrevVolume = 1.0;

    public Audio(Document document) {
        super(document, TAG_NAME);
    }

    @Override
    protected void onInitFromDom(Element origin) {
        syncFromAttributes();
        if (shouldPreload()) {
            ensurePlayback();
        }
    }

    @Override
    public void setAttribute(String name, String value) {
        String key = normalizeAttr(name);
        String beforeSrc = getAttribute(ATTR_SRC);
        super.setAttribute(name, value);
        if (Objects.equals(key, ATTR_SRC) && !Objects.equals(beforeSrc, getAttribute(ATTR_SRC))) {
            syncFromAttributes();
            restart();
            return;
        }
        if (Objects.equals(key, ATTR_AUTOPLAY) || Objects.equals(key, ATTR_LOOP) || Objects.equals(key, ATTR_PAUSED)
                || Objects.equals(key, ATTR_PRELOAD) || Objects.equals(key, ATTR_MUTED) || Objects.equals(key, ATTR_VOLUME)) {
            syncFromAttributes();
            applyRuntime();
        }
    }

    @Override
    public void removeAttribute(String name) {
        String key = normalizeAttr(name);
        String beforeSrc = getAttribute(ATTR_SRC);
        super.removeAttribute(name);
        if (Objects.equals(key, ATTR_SRC) && !Objects.equals(beforeSrc, getAttribute(ATTR_SRC))) {
            syncFromAttributes();
            restart();
            return;
        }
        if (Objects.equals(key, ATTR_AUTOPLAY) || Objects.equals(key, ATTR_LOOP) || Objects.equals(key, ATTR_PAUSED)
                || Objects.equals(key, ATTR_PRELOAD) || Objects.equals(key, ATTR_MUTED) || Objects.equals(key, ATTR_VOLUME)) {
            syncFromAttributes();
            applyRuntime();
        }
    }

    @Override
    public void remove() {
        closePlayback();
        super.remove();
    }

    @Override
    public void drawPhase(PoseStack poseStack, Base.RenderPhase phase) {
    }

    private void applyRuntime() {
        if (autoplay && playback == null && shouldPreload()) {
            ensurePlayback();
        }
        if (playback == null) return;
        if (paused != lastPaused) {
            playback.setPaused(paused);
            lastPaused = paused;
        }
        playback.setMuted(muted);
        playback.setVolume(volume);
    }

    private void restart() {
        closePlayback();
        if (shouldPreload()) {
            ensurePlayback();
        }
    }

    private void closePlayback() {
        AudioPlayback old = playback;
        playback = null;
        if (old != null) {
            old.close();
        }
        lastPaused = false;
    }

    public void closeMedia() {
        closePlayback();
    }

    private static String normalizeHlsPolicy(String value) {
        return MediaUtil.normalizeHlsPolicy(value);
    }

    private static boolean isRemoteUrl(String url) {
        return MediaUtil.isRemoteUrl(url);
    }

    private static HlsMasterPlaylist.Policy parsePolicy(String policy) {
        return MediaUtil.parsePolicy(policy, false);
    }

    @Override
    public void tick() {
        super.tick();
        if (resolvedSrc.isEmpty()) {
            networkState = 0;
            return;
        }
        if (playback == null && shouldPreload()) {
            ensurePlayback();
        }
        applyRuntime();
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

        preload = normalizePreload(getAttribute(ATTR_PRELOAD));
        autoplay = isTruthyAttr(ATTR_AUTOPLAY);
        loop = isTruthyAttr(ATTR_LOOP);
        paused = isTruthyAttr(ATTR_PAUSED);
        muted = isTruthyAttr(ATTR_MUTED);
        volume = clamp01(parseDouble(getAttribute(ATTR_VOLUME), 1.0));
        networkTimeoutMs = Math.max(1000, parseInt(getAttribute(ATTR_NETWORK_TIMEOUT_MS), 15000));
        networkBufferKb = Math.max(64, parseInt(getAttribute(ATTR_NETWORK_BUFFER_KB), 512));
        networkReconnect = parseBoolean(getAttribute(ATTR_NETWORK_RECONNECT), true);
        hlsPolicy = normalizeHlsPolicy(getAttribute(ATTR_HLS_POLICY));
    }

    private void ensurePlayback() {
        if (playback != null) return;
        if (resolvedSrc.isEmpty()) return;
        playback = AudioPlayback.open(resolveHlsForAudio(resolvedSrc), loop, muted, volume, networkTimeoutMs, networkBufferKb, networkReconnect, networkOptions);
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
        restart();
    }

    public double getCurrentTime() {
        return mediaCurrentTimeSecs;
    }

    private static HlsMasterPlaylist.Policy parseHlsPolicy(String policy) {
        return MediaUtil.parseHlsPolicy(policy, false);
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

    private static String normalizePreload(String value) {
        return MediaUtil.normalizePreload(value);
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
        if (playback != null) playback.setVolume(volume);
    }

    public boolean isMuted() {
        return muted;
    }

    public void setMuted(boolean m) {
        muted = m;
        setAttribute("muted", m ? "true" : "false");
        if (playback != null) playback.setMuted(m);
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

    private static double parseDouble(String value, double fallback) {
        return MediaUtil.parseDouble(value, fallback);
    }

    private static int parseInt(String value, int fallback) {
        return MediaUtil.parseInt(value, fallback);
    }

    private static boolean parseBoolean(String value, boolean fallback) {
        return MediaUtil.parseBoolean(value, fallback);
    }

    private static double clamp01(double value) {
        return MediaUtil.clamp01(value);
    }

    private static String normalizeAttr(String name) {
        return MediaUtil.normalizeAttr(name);
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

        // Update duration when we get it
        if (mediaDurationSecs < 0 && playback != null) {
            long durMs = playback.getDurationMs();
            if (durMs > 0) {
                mediaDurationSecs = durMs / 1000.0;
                dispatchMediaEvent("durationchange");
                readyState = Math.max(readyState, 1);
                dispatchMediaEvent("loadedmetadata");
            }
        }

        // Ready state transitions
        if (playback != null && readyState < 1) {
            readyState = 1;
            networkState = 1;
            dispatchMediaEvent("loadedmetadata");
        }
        if (playback != null && !mediaHasFiredCanPlay) {
            mediaHasFiredCanPlay = true;
            readyState = Math.max(readyState, 2);
            dispatchMediaEvent("canplay");
        }

        // Network state
        if (!resolvedSrc.isEmpty() && networkState == 0) {
            networkState = 1;
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
            mediaCurrentTimeSecs += 0.25; // approximate
            dispatchMediaEvent("timeupdate");
            mediaLastTimeUpdateMs = now;
        }

        // Detect ended state
        if (playing && playback != null && mediaLastTimeUpdateMs > 0 && mediaCurrentTimeSecs > 0.01 && mediaDurationSecs > 0 && mediaCurrentTimeSecs >= mediaDurationSecs - 0.5) {
            mediaEnded = true;
            dispatchMediaEvent("ended");
        }
    }

    public String hlsHighest(String masterUrl) {
        return HlsMasterPlaylist.selectVariantOrSelf(masterUrl, HlsMasterPlaylist.Policy.LOWEST_BANDWIDTH, 8000);
    }

    public void setUserAgent(String userAgent) {
        networkOptions.put("user_agent", userAgent);
    }

    public void setHeaders(String headers) {
        networkOptions.put("headers", headers);
    }

    public String hlsHighest(String masterUrl, int timeoutMs) {
        return HlsMasterPlaylist.selectVariantOrSelf(masterUrl, HlsMasterPlaylist.Policy.LOWEST_BANDWIDTH, timeoutMs);
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

    private boolean shouldPreload() {
        return MediaUtil.shouldPreload(autoplay, paused, preload);
    }

    private boolean isTruthyAttr(String name) {
        return MediaUtil.isTruthyAttr(this, name);
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

    private String resolveHlsForAudio(String url) {
        return MediaUtil.resolveHls(url, hlsPolicy, networkTimeoutMs, false);
    }
}
