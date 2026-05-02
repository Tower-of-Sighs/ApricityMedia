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

public final class BilibiliLiveUtil {
    private static final Logger LOGGER = LoggerFactory.getLogger(BilibiliLiveUtil.class);

    private static final String API_GET_INFO = "https://api.live.bilibili.com/room/v1/Room/get_info";
    private static final String API_PLAY_URL = "https://api.live.bilibili.com/room/v1/Room/playUrl";
    private static final String REFERER = "https://live.bilibili.com/";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36";
    private static final int TIMEOUT_MS = 8000;

    private BilibiliLiveUtil() {
    }

    public static String getPlayUrl(String roomId) {
        return getPlayUrl(roomId, 10000);
    }

    public static String getPlayUrl(String roomId, int qn) {
        if (roomId == null || roomId.isBlank()) return null;

        long realRoomId = resolveRoomId(roomId.trim());
        if (realRoomId <= 0) return null;

        JsonObject resp = httpGet(API_PLAY_URL + "?cid=" + realRoomId + "&qn=" + qn + "&platform=web");
        if (resp == null) return null;

        try {
            if (resp.get("code").getAsInt() != 0) return null;
            JsonArray durl = resp.getAsJsonObject("data").getAsJsonArray("durl");
            if (durl == null || durl.isEmpty()) return null;

            for (JsonElement el : durl) {
                JsonObject entry = el.getAsJsonObject();
                String url = entry.get("url").getAsString();
                if (url != null && !url.isBlank()) {
                    return url.replace("\\u0026", "&");
                }
            }
            return null;
        } catch (Exception e) {
            LOGGER.error("Bilibili playUrl parse error for room={}", realRoomId, e);
            return null;
        }
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
