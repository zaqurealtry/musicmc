package com.spotifymc.media;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Finds cover art for tracks whose player doesn't hand us any (e.g. Apple Music on macOS),
 * using Apple's free, keyless iTunes Search API.
 */
public class ArtworkLookup {

    private static final Logger LOGGER = LoggerFactory.getLogger("spotifymc");
    private static final int MAX_ENTRIES = 200;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** key -> artwork URL ("" = looked up, nothing found). */
    private final Map<String, String> cache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MAX_ENTRIES;
        }
    });
    private final Set<String> pending = Collections.synchronizedSet(new HashSet<>());

    /**
     * @return a cover URL if one is already known, otherwise null (and a lookup is started).
     */
    public String find(String title, String artist) {
        if (title == null || title.isBlank()) return null;
        String key = (artist == null ? "" : artist.toLowerCase(Locale.ROOT)) + "|" + title.toLowerCase(Locale.ROOT);
        String cached = cache.get(key);
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }
        if (pending.add(key)) {
            lookup(key, title, artist == null ? "" : artist);
        }
        return null;
    }

    private void lookup(String key, String title, String artist) {
        String term = URLEncoder.encode((artist + " " + title).trim(), StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://itunes.apple.com/search?media=music&entity=song&limit=5&term=" + term))
                .header("User-Agent", UserAgent.VALUE)
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenAccept(resp -> {
                    String url = resp.statusCode() == 200 ? pickArtwork(resp.body(), artist) : null;
                    cache.put(key, url == null ? "" : url);
                })
                .exceptionally(err -> {
                    LOGGER.debug("[Music MC] Artwork lookup failed: {}", err.getMessage());
                    return null; // don't cache, so it is retried for the next track change
                })
                .whenComplete((v, e) -> pending.remove(key));
    }

    static String pickArtwork(String json, String artist) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (!root.has("results") || !root.get("results").isJsonArray()) return null;
        JsonArray results = root.getAsJsonArray("results");
        String wanted = artist.toLowerCase(Locale.ROOT);
        String fallback = null;
        for (JsonElement el : results) {
            JsonObject r = el.getAsJsonObject();
            if (!r.has("artworkUrl100")) continue;
            String url = r.get("artworkUrl100").getAsString().replace("100x100bb", "300x300bb");
            String name = r.has("artistName") ? r.get("artistName").getAsString().toLowerCase(Locale.ROOT) : "";
            if (!wanted.isEmpty() && !name.isEmpty() && (name.contains(wanted) || wanted.contains(name))) {
                return url;
            }
            if (fallback == null) fallback = url;
        }
        return wanted.isEmpty() ? fallback : null;
    }
}
