package cc.sighs.apricitymedia.hls;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

public final class HlsMasterPlaylist {
    private static final Logger LOGGER = LoggerFactory.getLogger(HlsMasterPlaylist.class);
    private static final int CACHE_LIMIT = 128;
    private static final long CACHE_TTL_MS = 30_000L;
    private static final Object CACHE_LOCK = new Object();
    private static final LinkedHashMap<String, CacheEntry> CACHE = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
            return size() > CACHE_LIMIT;
        }
    };

    private HlsMasterPlaylist() {
    }

    public static String selectVariantOrSelf(String url) {
        return selectVariantOrSelf(url, Policy.HIGHEST_BANDWIDTH, 8000);
    }

    public static String selectVariantOrSelf(String url, Policy policy, int timeoutMs) {
        if (!looksLikeHttpUrl(url) || !looksLikeM3u8(url)) return url;

        String cacheKey = policy.name() + "|" + url;
        CacheEntry cached = cacheGet(cacheKey);
        if (cached != null) return cached.value;

        try {
            String text = downloadText(url, timeoutMs);
            if (text == null || text.isBlank()) return url;
            List<Variant> variants = parseVariants(url, text);
            if (variants.isEmpty()) return url;
            Variant selected = select(variants, policy);
            if (selected == null || selected.uri == null || selected.uri.isBlank()) return url;
            String resolved = selected.uri;
            cachePut(cacheKey, resolved);
            return resolved;
        } catch (Exception e) {
            LOGGER.debug("HLS variant selection failed, fallback to master url={}", url, e);
            return url;
        }
    }

    private static CacheEntry cacheGet(String key) {
        long now = System.currentTimeMillis();
        synchronized (CACHE_LOCK) {
            CacheEntry entry = CACHE.get(key);
            if (entry == null) return null;
            if (now - entry.atMs > CACHE_TTL_MS) {
                CACHE.remove(key);
                return null;
            }
            return entry;
        }
    }

    private static void cachePut(String key, String value) {
        long now = System.currentTimeMillis();
        synchronized (CACHE_LOCK) {
            CACHE.put(key, new CacheEntry(value, now));
        }
    }

    private static Variant select(List<Variant> variants, Policy policy) {
        Comparator<Variant> byBw = Comparator.comparingLong(v -> v.bandwidth);
        Comparator<Variant> byRes = Comparator.comparingInt((Variant v) -> v.width).thenComparingInt(v -> v.height);
        return switch (policy) {
            case LOWEST_BANDWIDTH -> variants.stream().min(byBw).orElse(null);
            case HIGHEST_RESOLUTION -> variants.stream().max(byRes).orElse(null);
            case LOWEST_RESOLUTION -> variants.stream().min(byRes).orElse(null);
            case HIGHEST_BANDWIDTH -> variants.stream().max(byBw).orElse(null);
        };
    }

    private static List<Variant> parseVariants(String masterUrl, String playlistText) {
        URI base = URI.create(masterUrl);
        String[] lines = playlistText.replace("\r", "").split("\n");
        List<Variant> variants = new ArrayList<>();
        Variant pending = null;

        for (String raw : lines) {
            if (raw == null) continue;
            String line = raw.trim();
            if (line.isEmpty()) continue;

            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                pending = Variant.fromStreamInf(line.substring("#EXT-X-STREAM-INF:".length()));
                continue;
            }
            if (line.startsWith("#")) {
                continue;
            }

            // URI line following EXT-X-STREAM-INF.
            if (pending != null) {
                try {
                    pending.uri = base.resolve(line).toString();
                } catch (Exception ignored) {
                    pending.uri = line;
                }
                variants.add(pending);
                pending = null;
            }
        }
        return variants;
    }

    private static String downloadText(String url, int timeoutMs) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1000, timeoutMs)))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(Math.max(1000, timeoutMs)))
                .header("User-Agent", "ApricityMedia/1.0")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("HTTP status " + status);
        }
        return response.body();
    }

    private static boolean looksLikeM3u8(String url) {
        if (url == null) return false;
        String v = url.trim().toLowerCase(Locale.ROOT);
        int q = v.indexOf('?');
        if (q >= 0) v = v.substring(0, q);
        int hash = v.indexOf('#');
        if (hash >= 0) v = v.substring(0, hash);
        return v.endsWith(".m3u8");
    }

    private static boolean looksLikeHttpUrl(String url) {
        if (url == null) return false;
        String v = url.trim().toLowerCase(Locale.ROOT);
        return v.startsWith("http://") || v.startsWith("https://");
    }

    public enum Policy {
        HIGHEST_BANDWIDTH,
        LOWEST_BANDWIDTH,
        HIGHEST_RESOLUTION,
        LOWEST_RESOLUTION
    }

    private static final class Variant {
        String uri;
        long bandwidth;
        int width;
        int height;

        static Variant fromStreamInf(String attrs) {
            Variant v = new Variant();
            if (attrs == null) return v;
            String[] parts = attrs.split(",");
            for (String p : parts) {
                if (p == null) continue;
                String part = p.trim();
                int eq = part.indexOf('=');
                if (eq <= 0) continue;
                String key = part.substring(0, eq).trim().toUpperCase(Locale.ROOT);
                String value = part.substring(eq + 1).trim();
                value = stripQuotes(value);
                if ("BANDWIDTH".equals(key)) {
                    v.bandwidth = parseLong(value, 0);
                } else if ("RESOLUTION".equals(key)) {
                    int x = value.indexOf('x');
                    if (x > 0) {
                        v.width = (int) parseLong(value.substring(0, x), 0);
                        v.height = (int) parseLong(value.substring(x + 1), 0);
                    }
                }
            }
            return v;
        }

        private static String stripQuotes(String value) {
            if (value == null) return "";
            String s = value.trim();
            if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
                return s.substring(1, s.length() - 1);
            }
            return s;
        }

        private static long parseLong(String value, long fallback) {
            if (value == null || value.isBlank()) return fallback;
            try {
                return Long.parseLong(value.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
    }

    private record CacheEntry(String value, long atMs) {
    }
}
