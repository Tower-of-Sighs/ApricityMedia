package cc.sighs.apricitymedia.element;

import cc.sighs.apricitymedia.audio.AudioPlayback;
import cc.sighs.apricitymedia.hls.HlsMasterPlaylist;
import com.mojang.blaze3d.vertex.PoseStack;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.instance.Loader;
import com.sighs.apricityui.registry.annotation.ElementRegister;
import com.sighs.apricityui.render.Base;

import java.util.HashMap;
import java.util.Locale;
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

    /** Select the highest-bandwidth variant from an HLS master playlist. */
    public static String hlsHighest(String masterUrl) {
        return HlsMasterPlaylist.selectVariantOrSelf(masterUrl, HlsMasterPlaylist.Policy.LOWEST_BANDWIDTH, 8000);
    }

    @Override
    public void remove() {
        closePlayback();
        super.remove();
    }

    @Override
    public void drawPhase(PoseStack poseStack, Base.RenderPhase phase) {
    }

    /** Select the highest-bandwidth variant with a custom timeout. */
    public static String hlsHighest(String masterUrl, int timeoutMs) {
        return HlsMasterPlaylist.selectVariantOrSelf(masterUrl, HlsMasterPlaylist.Policy.LOWEST_BANDWIDTH, timeoutMs);
    }

    /** Select the lowest-bandwidth variant from an HLS master playlist (audio default). */
    public static String hlsLowest(String masterUrl) {
        return HlsMasterPlaylist.selectVariantOrSelf(masterUrl, HlsMasterPlaylist.Policy.LOWEST_BANDWIDTH, 8000);
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

    // ── Browser-standard media control API ──

    /** Select the lowest-bandwidth variant with a custom timeout. */
    public static String hlsLowest(String masterUrl, int timeoutMs) {
        return HlsMasterPlaylist.selectVariantOrSelf(masterUrl, HlsMasterPlaylist.Policy.LOWEST_BANDWIDTH, timeoutMs);
    }

    /** Select an HLS variant by policy name. */
    public static String hlsSelect(String masterUrl, String policyName) {
        return hlsSelect(masterUrl, policyName, 8000);
    }

    /** Select an HLS variant by policy name with a custom timeout. */
    public static String hlsSelect(String masterUrl, String policyName, int timeoutMs) {
        HlsMasterPlaylist.Policy policy = parseHlsPolicy(policyName);
        return HlsMasterPlaylist.selectVariantOrSelf(masterUrl, policy, timeoutMs);
    }

    // ── Media property getters ──

    private static HlsMasterPlaylist.Policy parseHlsPolicy(String policyName) {
        if (policyName == null || policyName.isBlank()) return HlsMasterPlaylist.Policy.LOWEST_BANDWIDTH;
        String v = policyName.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "highest", "high", "highest_bandwidth", "highest-bandwidth", "best" -> HlsMasterPlaylist.Policy.HIGHEST_BANDWIDTH;
            case "highest_resolution", "highest-resolution", "hires", "resolution" -> HlsMasterPlaylist.Policy.HIGHEST_RESOLUTION;
            case "lowest_resolution", "lowest-resolution", "lores" -> HlsMasterPlaylist.Policy.LOWEST_RESOLUTION;
            default -> HlsMasterPlaylist.Policy.LOWEST_BANDWIDTH;
        };
    }

    private static String normalizeHlsPolicy(String value) {
        if (value == null || value.isBlank()) return "auto";
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isRemoteUrl(String url) {
        if (url == null) return false;
        String v = url.trim().toLowerCase();
        return v.startsWith("http://") || v.startsWith("https://")
                || v.startsWith("rtsp://") || v.startsWith("rtmp://") || v.startsWith("mms://");
    }

    private static HlsMasterPlaylist.Policy parsePolicy(String policy) {
        if (policy == null || policy.isBlank()) return HlsMasterPlaylist.Policy.LOWEST_BANDWIDTH;
        return switch (policy) {
            case "highest", "high", "highest_bandwidth", "highest-bandwidth", "best" -> HlsMasterPlaylist.Policy.HIGHEST_BANDWIDTH;
            case "highest_resolution", "highest-resolution", "hires", "resolution", "bestres" -> HlsMasterPlaylist.Policy.HIGHEST_RESOLUTION;
            case "lowest_resolution", "lowest-resolution", "lores" -> HlsMasterPlaylist.Policy.LOWEST_RESOLUTION;
            default -> HlsMasterPlaylist.Policy.LOWEST_BANDWIDTH;
        };
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

    /** Start or resume playback. Equivalent to setting autoplay=true and paused=false. */
    public void play() {
        autoplay = true;
        paused = false;
        mediaEnded = false;
        setAttribute("autoplay", "true");
        setAttribute("paused", "false");
    }

    /** Pause playback. Equivalent to setting paused=true. */
    public void pause() {
        paused = true;
        setAttribute("paused", "true");
    }

    /** Reload the media source from the current src attribute. */
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

    /** Current playback position in seconds (estimated), or 0 if unknown. */
    public double getCurrentTime() {
        return mediaCurrentTimeSecs;
    }

    // ── Media property setters ──

    /** Media duration in seconds, or -1 if unknown (maps to NaN in JS). */
    public double getDuration() {
        return mediaDurationSecs;
    }

    /** Whether playback is currently paused. */
    public boolean isPaused() {
        return paused;
    }

    /** Whether playback has reached the end of the media. */
    public boolean isEnded() {
        return mediaEnded;
    }

    // ── HLS variant selection (callable from JS) ──

    /** HTMLMediaElement readyState. */
    public int getReadyState() {
        return readyState;
    }

    /** HTMLMediaElement networkState. */
    public int getNetworkState() {
        return networkState;
    }

    /** Current source URL. */
    public String getSrc() {
        return resolvedSrc;
    }

    /** Current volume [0.0, 1.0]. */
    public double getVolume() {
        return volume;
    }

    /** Set volume [0.0, 1.0]. */
    public void setVolume(double v) {
        volume = clamp01(v);
        setAttribute("volume", String.valueOf(volume));
        if (playback != null) playback.setVolume(volume);
    }

    /** Whether audio is muted. */
    public boolean isMuted() {
        return muted;
    }

    /** Set muted state. */
    public void setMuted(boolean m) {
        muted = m;
        setAttribute("muted", m ? "true" : "false");
        if (playback != null) playback.setMuted(m);
    }

    // ── Media event dispatching ──

    /** Whether the media loops. */
    public boolean isLoop() {
        return loop;
    }

    /** Set loop state. */
    public void setLoop(boolean l) {
        loop = l;
        setAttribute("loop", l ? "true" : "false");
    }

    // ── Network options API (callable from JS) ──

    /** Whether autoplay is enabled. */
    public boolean isAutoplay() {
        return autoplay;
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

    private void dispatchMediaEvent(String type) {
        try {
            com.sighs.apricityui.init.Event ev = new com.sighs.apricityui.init.Event(this, type, e -> {}, false);
            com.sighs.apricityui.init.Event.triggerSingle(ev);
        } catch (Exception ignored) {
        }
    }

    /** Set a custom User-Agent header for this media element's network streams. */
    public void setUserAgent(String userAgent) {
        networkOptions.put("user_agent", userAgent);
    }

    /** Set custom HTTP headers (FFmpeg format: "Key: value\r\nKey: value"). */
    public void setHeaders(String headers) {
        networkOptions.put("headers", headers);
    }

    private boolean shouldPreload() {
        if (autoplay && !paused) return true;
        return !preload.equals("none");
    }

    private boolean isTruthyAttr(String name) {
        String value = getAttribute(name);
        if (value == null) return false;
        if (value.isBlank()) return hasAttribute(name);
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return "true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized) || name.equals(normalized);
    }

    private static String normalizePreload(String value) {
        if (value == null || value.isBlank()) return "auto";
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "none", "metadata", "auto" -> normalized;
            default -> "auto";
        };
    }

    private static double parseDouble(String value, double fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Integer.parseInt(value.trim());
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

    private static String normalizeAttr(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    /** Set an arbitrary FFmpeg network option (e.g. "referer", "cookies", "user_agent"). */
    public void setNetworkOption(String key, String value) {
        networkOptions.put(key, value);
    }

    /** Remove all custom network options for this element. */
    public void clearNetworkOptions() {
        networkOptions.clear();
    }

    /** Get a snapshot of current custom network options. */
    public Map<String, String> getNetworkOptions() {
        return new HashMap<>(networkOptions);
    }

    private String resolveHlsForAudio(String url) {
        String policy = hlsPolicy;
        if (policy == null || policy.isBlank() || "auto".equals(policy)) {
            // Default: audio-only prefers lowest bandwidth.
            return HlsMasterPlaylist.selectVariantOrSelf(url, HlsMasterPlaylist.Policy.LOWEST_BANDWIDTH, networkTimeoutMs);
        }
        if ("off".equals(policy) || "disabled".equals(policy) || "none".equals(policy)) {
            return url;
        }
        return HlsMasterPlaylist.selectVariantOrSelf(url, parsePolicy(policy), networkTimeoutMs);
    }
}
