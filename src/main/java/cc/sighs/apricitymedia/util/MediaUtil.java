package cc.sighs.apricitymedia.util;

import cc.sighs.apricitymedia.hls.HlsMasterPlaylist;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.init.Event;

import java.util.Locale;

public final class MediaUtil {
    private MediaUtil() {
    }

    // ── Parsing ──

    public static int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    public static double parseDouble(String value, double fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    public static boolean parseBoolean(String value, boolean fallback) {
        if (value == null) return fallback;
        if (value.isBlank()) return true;
        String v = value.trim().toLowerCase(Locale.ROOT);
        return "true".equals(v) || "1".equals(v) || "yes".equals(v) || "on".equals(v);
    }

    public static double clamp01(double value) {
        return Math.max(0, Math.min(1, value));
    }

    // ── Normalization ──

    public static String normalizeAttr(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    public static String normalizePreload(String value) {
        if (value == null || value.isBlank()) return "auto";
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "none", "metadata", "auto" -> value.trim().toLowerCase(Locale.ROOT);
            default -> "auto";
        };
    }

    public static String normalizeHlsPolicy(String value) {
        if (value == null || value.isBlank()) return "auto";
        return value.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean isRemoteUrl(String url) {
        if (url == null) return false;
        String v = url.trim().toLowerCase(Locale.ROOT);
        //noinspection HttpUrlsUsage
        return v.startsWith("http://") || v.startsWith("https://")
                || v.startsWith("rtsp://") || v.startsWith("rtmp://") || v.startsWith("mms://");
    }

    public static boolean isTruthyAttr(Element element, String name) {
        String value = element.getAttribute(name);
        if (value == null) return false;
        if (value.isBlank()) return element.hasAttribute(name);
        String v = value.trim().toLowerCase(Locale.ROOT);
        return "true".equals(v) || "1".equals(v) || "yes".equals(v) || name.equals(v);
    }

    public static boolean shouldPreload(boolean autoplay, boolean paused, String preload) {
        if (autoplay && !paused) return true;
        return !"none".equals(preload);
    }

    public static boolean shouldRestartForKey(String key) {
        return "decode-width".equals(key) || "decode-height".equals(key)
                || "max-fps".equals(key) || "drop-frames".equals(key);
    }

    // ── HLS ──

    public static HlsMasterPlaylist.Policy parsePolicy(String policy, boolean isVideo) {
        if (policy == null || policy.isBlank())
            return isVideo ? HlsMasterPlaylist.Policy.HIGHEST_BANDWIDTH : HlsMasterPlaylist.Policy.LOWEST_BANDWIDTH;
        return switch (policy.trim().toLowerCase(Locale.ROOT)) {
            case "highest", "high", "highest_bandwidth", "highest-bandwidth", "best" ->
                    HlsMasterPlaylist.Policy.HIGHEST_BANDWIDTH;
            case "lowest", "low", "lowest_bandwidth", "lowest-bandwidth" -> HlsMasterPlaylist.Policy.LOWEST_BANDWIDTH;
            case "highest_resolution", "highest-resolution", "hires", "resolution", "bestres" ->
                    HlsMasterPlaylist.Policy.HIGHEST_RESOLUTION;
            case "lowest_resolution", "lowest-resolution", "lores" -> HlsMasterPlaylist.Policy.LOWEST_RESOLUTION;
            default -> isVideo ? HlsMasterPlaylist.Policy.HIGHEST_BANDWIDTH : HlsMasterPlaylist.Policy.LOWEST_BANDWIDTH;
        };
    }

    public static HlsMasterPlaylist.Policy parseHlsPolicy(String policy, boolean isVideo) {
        return parsePolicy(policy, isVideo);
    }

    public static String resolveHls(String url, String hlsPolicy, int networkTimeoutMs, boolean isVideo) {
        if (hlsPolicy == null || hlsPolicy.isBlank() || "auto".equals(hlsPolicy)) {
            HlsMasterPlaylist.Policy def = isVideo ? HlsMasterPlaylist.Policy.HIGHEST_BANDWIDTH : HlsMasterPlaylist.Policy.LOWEST_BANDWIDTH;
            return HlsMasterPlaylist.selectVariantOrSelf(url, def, networkTimeoutMs);
        }
        if ("off".equals(hlsPolicy) || "disabled".equals(hlsPolicy) || "none".equals(hlsPolicy)) return url;
        return HlsMasterPlaylist.selectVariantOrSelf(url, parsePolicy(hlsPolicy, isVideo), networkTimeoutMs);
    }

    // ── Events ──

    public static void dispatchMediaEvent(Element element, String type) {
        Event ev = new Event(element, type, e -> {
        }, false);
        Event.triggerSingle(ev);
    }
}
