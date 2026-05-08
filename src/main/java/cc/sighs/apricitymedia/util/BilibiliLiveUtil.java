package cc.sighs.apricitymedia.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class BilibiliLiveUtil {
    private static final Logger LOGGER = LoggerFactory.getLogger(BilibiliLiveUtil.class);

    private static final String API_GET_INFO = "https://api.live.bilibili.com/room/v1/Room/get_info";
    private static final String API_PLAY_URL = "https://api.live.bilibili.com/room/v1/Room/playUrl";
    private static final String REFERER = "https://live.bilibili.com/";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36";
    private static final int TIMEOUT_MS = 8000;
    private static final ConcurrentMap<String, List<String>> URL_GROUP_CACHE = new ConcurrentHashMap<>();

    private BilibiliLiveUtil() {
    }

    public static String getPlayUrl(String roomId) {
        return getPlayUrl(roomId, 10000);
    }

    public static String getPlayUrl(String roomId, int qn) {
        List<String> urls = getPlayUrls(roomId, qn);
        return urls.isEmpty() ? null : urls.get(0);
    }

    public static List<String> getPlayUrls(String roomId) {
        return getPlayUrls(roomId, 10000);
    }

    public static List<String> getPlayUrls(String roomId, int qn) {
        if (roomId == null || roomId.isBlank()) return List.of();

        long realRoomId = resolveRoomId(roomId.trim());
        if (realRoomId <= 0) return List.of();

        JsonObject resp = httpGet(API_PLAY_URL + "?cid=" + realRoomId + "&qn=" + qn + "&platform=web");
        if (resp == null) return List.of();

        try {
            if (resp.get("code").getAsInt() != 0) return List.of();
            JsonArray durl = resp.getAsJsonObject("data").getAsJsonArray("durl");
            if (durl == null || durl.isEmpty()) return List.of();

            List<String> candidates = collectCandidates(durl);
            registerUrlGroup(candidates);
            return candidates;
        } catch (Exception e) {
            LOGGER.error("Bilibili playUrl parse error for room={}", realRoomId, e);
            return List.of();
        }
    }

    /**
     * Given any URL from a previously resolved Bilibili group, return an ordered
     * fallback chain starting from that URL and continuing with remaining candidates.
     */
    public static List<String> getFallbackChain(String resolvedUrl) {
        if (resolvedUrl == null || resolvedUrl.isBlank()) return List.of();
        List<String> chain = URL_GROUP_CACHE.get(resolvedUrl);
        if (chain == null || chain.isEmpty()) return List.of(resolvedUrl);
        return chain;
    }

    private static List<String> collectCandidates(JsonArray durl) {
        Set<String> ordered = new LinkedHashSet<>();
        for (JsonElement el : durl) {
            if (el == null || !el.isJsonObject()) continue;
            JsonObject entry = el.getAsJsonObject();

            String primary = readUrl(entry, "url");
            if (primary != null) ordered.add(primary);

            JsonElement backupEl = entry.get("backup_url");
            if (backupEl != null && backupEl.isJsonArray()) {
                for (JsonElement b : backupEl.getAsJsonArray()) {
                    if (b == null || b.isJsonNull()) continue;
                    String backup = decodeUrl(b.getAsString());
                    if (backup != null) ordered.add(backup);
                }
            }
        }
        return new ArrayList<>(ordered);
    }

    private static void registerUrlGroup(List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) return;
        int n = candidates.size();
        for (int i = 0; i < n; i++) {
            String current = candidates.get(i);
            if (current == null || current.isBlank()) continue;
            List<String> chain = new ArrayList<>(n);
            for (int j = i; j < n; j++) chain.add(candidates.get(j));
            for (int j = 0; j < i; j++) chain.add(candidates.get(j));
            URL_GROUP_CACHE.put(current, Collections.unmodifiableList(chain));
        }
    }

    private static String readUrl(JsonObject obj, String key) {
        if (obj == null || key == null) return null;
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) return null;
        return decodeUrl(el.getAsString());
    }

    private static String decodeUrl(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return raw.replace("\\u0026", "&");
    }

    private static long resolveRoomId(String roomId) {
        JsonObject resp = httpGet(API_GET_INFO + "?room_id=" + roomId);
        if (resp == null) return -1;
        try {
            if (resp.get("code").getAsInt() != 0) {
                try {
                    return Long.parseLong(roomId);
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
            return resp.getAsJsonObject("data").get("room_id").getAsLong();
        } catch (Exception e) {
            LOGGER.error("Bilibili resolveRoomId parse error for room={}", roomId, e);
            return -1;
        }
    }

    private static JsonObject httpGet(String urlString) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlString).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("Referer", REFERER);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Accept", "application/json");
            conn.setInstanceFollowRedirects(true);

            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) return null;

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
}
