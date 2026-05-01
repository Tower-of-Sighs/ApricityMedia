package cc.sighs.apricitymedia.kjs;

import cc.sighs.apricitymedia.ApricityMedia;
import cc.sighs.apricitymedia.hls.HlsMasterPlaylist;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sighs.apricityui.registry.annotation.KJSBindings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Global media utilities exposed to KubeJS.
 *
 * <p>Includes HLS master playlist selection and Bilibili live-stream API integration.
 * For browser-standard media controls ({@code play()}, {@code pause()},
 * {@code getCurrentTime()}, etc.), use the methods directly on {@code <video>}
 * and {@code <audio>} element instances instead.
 */
@KJSBindings(value = "ApricityMediaHls", modId = ApricityMedia.MOD_ID, isClient = true)
public final class ApricityMediaHls {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApricityMediaHls.class);

    private static final String BILIBILI_GET_INFO = "https://api.live.bilibili.com/room/v1/Room/get_info";
    private static final String BILIBILI_PLAY_URL = "https://api.live.bilibili.com/room/v1/Room/playUrl";
    private static final String BILIBILI_REFERER = "https://live.bilibili.com/";
    private static final String BILIBILI_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36";
    private static final int HTTP_TIMEOUT_MS = 8000;

    private ApricityMediaHls() {
    }

    // ── HLS variant selection ──

    public static String highest(String masterUrl) {
        return HlsMasterPlaylist.selectVariantOrSelf(masterUrl, HlsMasterPlaylist.Policy.HIGHEST_BANDWIDTH, 8000);
    }

    public static String highest(String masterUrl, int timeoutMs) {
        return HlsMasterPlaylist.selectVariantOrSelf(masterUrl, HlsMasterPlaylist.Policy.HIGHEST_BANDWIDTH, timeoutMs);
    }

    public static String lowest(String masterUrl) {
        return HlsMasterPlaylist.selectVariantOrSelf(masterUrl, HlsMasterPlaylist.Policy.LOWEST_BANDWIDTH, 8000);
    }

    public static String lowest(String masterUrl, int timeoutMs) {
        return HlsMasterPlaylist.selectVariantOrSelf(masterUrl, HlsMasterPlaylist.Policy.LOWEST_BANDWIDTH, timeoutMs);
    }

    public static String select(String masterUrl, String policyName) {
        return select(masterUrl, policyName, 8000);
    }

    public static String select(String masterUrl, String policyName, int timeoutMs) {
        HlsMasterPlaylist.Policy policy = parsePolicy(policyName);
        return HlsMasterPlaylist.selectVariantOrSelf(masterUrl, policy, timeoutMs);
    }

    public static String resolve(String url) {
        return HlsMasterPlaylist.selectVariantOrSelf(url, HlsMasterPlaylist.Policy.HIGHEST_BANDWIDTH, 8000);
    }

    public static String resolve(String url, int timeoutMs) {
        return HlsMasterPlaylist.selectVariantOrSelf(url, HlsMasterPlaylist.Policy.HIGHEST_BANDWIDTH, timeoutMs);
    }

    // ── Bilibili live-stream API ──

    /**
     * Get a playable Bilibili live-stream URL for the given room ID.
     *
     * <p>Resolves short room IDs to real IDs, checks live status, and returns
     * the best available stream URL (highest quality, first CDN line).
     *
     * @param roomId Bilibili room ID (supports short IDs)
     * @return the stream URL, or null if the room is not live / not found / on error
     */
    public static String getBilibiliPlayUrl(String roomId) {
        return getBilibiliPlayUrl(roomId, 10000);
    }

    /**
     * Get a playable Bilibili live-stream URL for the given room ID with a
     * specific quality.
     *
     * @param roomId Bilibili room ID (supports short IDs)
     * @param qn     quality number: 80=流畅, 150=高清, 250=超清, 400=蓝光, 10000=原画
     * @return the stream URL, or null if the room is not live / not found / on error
     */
    public static String getBilibiliPlayUrl(String roomId, int qn) {
        if (roomId == null || roomId.isBlank()) return null;

        // Step 1: resolve short ID → real room ID
        long realRoomId = resolveBilibiliRoomId(roomId.trim());
        if (realRoomId <= 0) {
            LOGGER.warn("Bilibili resolveRoomId failed for input={}", roomId);
            return null;
        }

        // Step 2: fetch the play URL
        String apiUrl = BILIBILI_PLAY_URL + "?cid=" + realRoomId + "&qn=" + qn + "&platform=web";
        JsonObject resp = httpGetJson(apiUrl);
        if (resp == null) {
            LOGGER.warn("Bilibili playUrl API call failed for room={}", realRoomId);
            return null;
        }

        try {
            int code = resp.has("code") ? resp.get("code").getAsInt() : -1;
            if (code != 0) {
                String msg = resp.has("message") ? resp.get("message").getAsString() : "";
                LOGGER.warn("Bilibili playUrl API returned code={} msg={} for room={}", code, msg, realRoomId);
                return null;
            }

            JsonObject data = resp.getAsJsonObject("data");
            JsonArray durl = data.getAsJsonArray("durl");
            if (durl == null || durl.isEmpty()) {
                LOGGER.warn("Bilibili playUrl returned empty durl for room={}", realRoomId);
                return null;
            }

            // Pick the first CDN line (order=1 or the first entry)
            String streamUrl = null;
            for (JsonElement el : durl) {
                JsonObject entry = el.getAsJsonObject();
                String url = entry.get("url").getAsString();
                if (url != null && !url.isBlank()) {
                    // prefer order=1, but accept any valid URL
                    streamUrl = url.replace("\\u0026", "&");
                    if (entry.has("order") && entry.get("order").getAsInt() == 1) {
                        break;
                    }
                }
            }
            if (streamUrl == null || streamUrl.isBlank()) {
                LOGGER.warn("Bilibili playUrl durl entries had no valid URL for room={}", realRoomId);
                return null;
            }

            LOGGER.info("Bilibili playUrl resolved room={} qn={} -> {}", realRoomId, qn, streamUrl);
            return streamUrl;
        } catch (Exception e) {
            LOGGER.error("Bilibili playUrl parse error for room={}", realRoomId, e);
            return null;
        }
    }

    /**
     * Get Bilibili room info as a JSON string.
     *
     * <p>Returns JSON with fields: room_id, uid, title, live_status (0=offline, 1=live, 2=rotating),
     * online, area_name, description, user_cover, live_time, tags.
     * Returns null on error.
     */
    public static String getBilibiliRoomInfo(String roomId) {
        if (roomId == null || roomId.isBlank()) return null;
        JsonObject resp = httpGetJson(BILIBILI_GET_INFO + "?room_id=" + roomId.trim());
        if (resp == null) return null;
        try {
            int code = resp.has("code") ? resp.get("code").getAsInt() : -1;
            if (code != 0) return null;
            JsonObject data = resp.getAsJsonObject("data");
            // Build a simplified info object
            JsonObject info = new JsonObject();
            info.addProperty("room_id", jsonLong(data, "room_id"));
            info.addProperty("uid", jsonLong(data, "uid"));
            info.addProperty("title", jsonStr(data, "title"));
            info.addProperty("live_status", jsonInt(data, "live_status"));
            info.addProperty("online", jsonInt(data, "online"));
            info.addProperty("area_name", jsonStr(data, "area_name"));
            info.addProperty("description", jsonStr(data, "description"));
            info.addProperty("user_cover", jsonStr(data, "user_cover"));
            info.addProperty("live_time", jsonStr(data, "live_time"));
            info.addProperty("tags", jsonStr(data, "tags"));
            return info.toString();
        } catch (Exception e) {
            LOGGER.error("Bilibili getRoomInfo parse error for room={}", roomId, e);
            return null;
        }
    }

    /**
     * Resolve a Bilibili room ID (which may be a short ID) to the real room ID.
     *
     * @return the real room ID, or -1 on failure
     */
    public static long resolveBilibiliRoomId(String roomId) {
        // First try the get_info API which always returns the real room_id
        JsonObject resp = httpGetJson(BILIBILI_GET_INFO + "?room_id=" + roomId);
        if (resp == null) return -1;
        try {
            int code = resp.has("code") ? resp.get("code").getAsInt() : -1;
            if (code != 0) {
                // If get_info fails, the input might already be the real ID; return it as-is
                try {
                    return Long.parseLong(roomId);
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
            JsonObject data = resp.getAsJsonObject("data");
            return data.has("room_id") ? data.get("room_id").getAsLong() : -1;
        } catch (Exception e) {
            LOGGER.error("Bilibili resolveRoomId parse error for room={}", roomId, e);
            return -1;
        }
    }

    // ── HTTP helper ──

    private static JsonObject httpGetJson(String urlString) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(HTTP_TIMEOUT_MS);
            conn.setReadTimeout(HTTP_TIMEOUT_MS);
            conn.setRequestProperty("Referer", BILIBILI_REFERER);
            conn.setRequestProperty("User-Agent", BILIBILI_UA);
            conn.setRequestProperty("Accept", "application/json");
            conn.setInstanceFollowRedirects(true);

            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                LOGGER.warn("HTTP {} for {}", status, urlString);
                return null;
            }

            try (InputStreamReader reader = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        } catch (Exception e) {
            LOGGER.warn("HTTP GET failed for {}: {}", urlString, e.toString());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ── JSON helpers ──

    private static String jsonStr(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : "";
    }

    private static int jsonInt(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsInt() : 0;
    }

    private static long jsonLong(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsLong() : 0;
    }

    // ── HLS policy parsing ──

    private static HlsMasterPlaylist.Policy parsePolicy(String policyName) {
        if (policyName == null || policyName.isBlank()) return HlsMasterPlaylist.Policy.HIGHEST_BANDWIDTH;
        String v = policyName.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "lowest", "low", "lowest_bandwidth", "lowest-bandwidth" -> HlsMasterPlaylist.Policy.LOWEST_BANDWIDTH;
            case "highest_resolution", "highest-resolution", "hires", "resolution" -> HlsMasterPlaylist.Policy.HIGHEST_RESOLUTION;
            case "lowest_resolution", "lowest-resolution", "lores" -> HlsMasterPlaylist.Policy.LOWEST_RESOLUTION;
            default -> HlsMasterPlaylist.Policy.HIGHEST_BANDWIDTH;
        };
    }
}
