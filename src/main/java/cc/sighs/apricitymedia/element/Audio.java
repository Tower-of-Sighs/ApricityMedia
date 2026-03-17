package cc.sighs.apricitymedia.element;

import cc.sighs.apricitymedia.audio.AudioPlayback;
import com.mojang.blaze3d.vertex.PoseStack;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.instance.Loader;
import com.sighs.apricityui.registry.annotation.ElementRegister;
import com.sighs.apricityui.render.Base;

import java.util.Locale;
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

    private AudioPlayback playback;
    private boolean lastPaused = false;

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
    public void tick() {
        super.tick();
        if (resolvedSrc.isEmpty()) return;
        if (playback == null && shouldPreload()) {
            ensurePlayback();
        }
        applyRuntime();
    }

    @Override
    public void remove() {
        closePlayback();
        super.remove();
    }

    @Override
    public void drawPhase(PoseStack poseStack, Base.RenderPhase phase) {
    }

    private void syncFromAttributes() {
        String rawSrc = getAttribute(ATTR_SRC);
        if (rawSrc == null || rawSrc.isBlank()) {
            resolvedSrc = "";
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
    }

    private void ensurePlayback() {
        if (playback != null) return;
        if (resolvedSrc.isEmpty()) return;
        playback = AudioPlayback.open(resolvedSrc, loop, muted, volume, networkTimeoutMs, networkBufferKb, networkReconnect);
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
}
