package com.spotifymc.lyrics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.spotifymc.api.SpotifyPlaybackState;
import com.spotifymc.media.UserAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.text.Normalizer;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * Fetches lyrics for whatever is playing from LRCLIB (https://lrclib.net), a free, open lyrics
 * database that needs no account or API key. Matching is done on title / artist / album /
 * duration, so it works the same no matter which app (Spotify, Apple Music, ...) is playing.
 */
public class LyricsService {

    private static final Logger LOGGER = LoggerFactory.getLogger("spotifymc");
    private static final String API = "https://lrclib.net/api";
    private static final long ERROR_RETRY_MS = 30_000;
    private static final int MAX_CACHE = 64;

    public enum Status { IDLE, LOADING, FOUND, NOT_FOUND, ERROR }

    public record Result(String trackKey, Status status, Lyrics lyrics) {
        static Result idle() {
            return new Result("", Status.IDLE, null);
        }
    }

    private final BooleanSupplier enabled;
    private final Path localLyricsDir;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MusicMC-Lyrics");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Result> cache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Result> eldest) {
            return size() > MAX_CACHE;
        }
    });

    private volatile Result current = Result.idle();
    private volatile long lastErrorAt = 0;

    public LyricsService(BooleanSupplier enabled) {
        this(enabled, Path.of("config", "spotifymc", "lyrics"));
    }

    public LyricsService(BooleanSupplier enabled, Path configDir) {
        this.enabled = enabled;
        this.localLyricsDir = configDir.resolve("lyrics");
        try {
            Files.createDirectories(this.localLyricsDir);
        } catch (IOException e) {
            LOGGER.warn("[Music MC] Could not create local lyrics folder: {}", e.getMessage());
        }
    }

    /**
     * Called after every playback poll. Starts a lookup when the track changes.
     */
    public void onStateUpdate(SpotifyPlaybackState state) {
        if (!enabled.getAsBoolean() || state == null || !state.hasTrack()) {
            return;
        }
        String key = trackKey(state);
        Result now = current;
        if (key.equals(now.trackKey())) {
            boolean retry = now.status() == Status.ERROR && System.currentTimeMillis() - lastErrorAt > ERROR_RETRY_MS;
            if (!retry) return;
        } else {
            Result cached = cache.get(key);
            if (cached != null) {
                current = cached;
                return;
            }
        }

        current = new Result(key, Status.LOADING, null);
        String title = state.trackName();
        String artist = state.artistName();
        String album = state.albumName();
        long durationMs = state.durationMs();

        executor.execute(() -> {
            Result result;
            try {
                Lyrics lyrics = lookup(title, artist, album, durationMs);
                if (lyrics == null) lyrics = lookupLocal(title, artist);
                result = new Result(key, lyrics == null ? Status.NOT_FOUND : Status.FOUND, lyrics);
                cache.put(key, result);
            } catch (Exception e) {
                // Network lookup may fail while the user still has a local .lrc file.
                // Always try the local fallback before reporting an error.
                Lyrics local = lookupLocal(title, artist);
                if (local != null) {
                    result = new Result(key, Status.FOUND, local);
                    cache.put(key, result);
                } else {
                    LOGGER.warn("[Music MC] Lyrics lookup failed for '{}': {}", title, e.getMessage());
                    lastErrorAt = System.currentTimeMillis();
                    result = new Result(key, Status.ERROR, null);
                }
            }
            if (key.equals(current.trackKey())) {
                current = result;
            }
        });
    }

    /**
     * Lyrics state for the given track (IDLE if we have nothing for it yet).
     */
    public Result resultFor(SpotifyPlaybackState state) {
        if (state == null || !state.hasTrack()) return Result.idle();
        Result r = current;
        return r.trackKey().equals(trackKey(state)) ? r : Result.idle();
    }

    public void clearCache() {
        cache.clear();
        current = Result.idle();
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    static String trackKey(SpotifyPlaybackState s) {
        return normalize(s.artistName()) + "|" + normalize(s.trackName());
    }

    // ----------------------------------------------------------------------------------------
    // LRCLIB lookup
    // ----------------------------------------------------------------------------------------

    private Lyrics lookup(String title, String artist, String album, long durationMs) throws IOException, InterruptedException {
        // 1. Exact signature match (fast and accurate when we have all four fields).
        if (album != null && !album.isBlank() && durationMs > 0) {
            String url = API + "/get?track_name=" + enc(title) + "&artist_name=" + enc(artist)
                    + "&album_name=" + enc(album) + "&duration=" + Math.round(durationMs / 1000.0);
            HttpResponse<String> resp = get(url);
            if (resp.statusCode() == 200) {
                Lyrics l = toLyrics(JsonParser.parseString(resp.body()).getAsJsonObject());
                if (l != null) return l;
            } else if (resp.statusCode() != 404) {
                throw new IOException("LRCLIB returned HTTP " + resp.statusCode());
            }
        }

        // 2. Search with the names as reported, then 3. with "(feat. ...)" / " - Remastered" etc. removed.
        Lyrics found = search(title, artist, title, artist, durationMs);
        if (found != null) return found;

        String cleanTitle = cleanTitle(title);
        String mainArtist = primaryArtist(artist);
        if (!cleanTitle.equals(title) || !mainArtist.equals(artist)) {
            found = search(cleanTitle, mainArtist, title, artist, durationMs);
        }
        return found;
    }

    private Lyrics search(String queryTitle, String queryArtist, String title, String artist, long durationMs)
            throws IOException, InterruptedException {
        String url = API + "/search?track_name=" + enc(queryTitle)
                + (queryArtist == null || queryArtist.isBlank() ? "" : "&artist_name=" + enc(queryArtist));
        HttpResponse<String> resp = get(url);
        if (resp.statusCode() != 200) {
            if (resp.statusCode() == 404) return null;
            throw new IOException("LRCLIB returned HTTP " + resp.statusCode());
        }
        JsonElement root = JsonParser.parseString(resp.body());
        if (!root.isJsonArray()) return null;
        JsonObject best = pickBest(root.getAsJsonArray(), title, artist, durationMs);
        return best == null ? null : toLyrics(best);
    }

    private HttpResponse<String> get(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", UserAgent.VALUE)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /**
     * Fallback to user-provided .lrc files in config/spotifymc/lyrics.
     * Accepted names include "Artist - Title.lrc" and "Title.lrc".
     */
    private Lyrics lookupLocal(String title, String artist) {
        if (!Files.isDirectory(localLyricsDir)) return null;
        String cleanTitle = fileKey(title);
        String cleanArtist = fileKey(artist);
        try (var files = Files.list(localLyricsDir)) {
            var candidates = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".lrc"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .toList();
            for (Path file : candidates) {
                String name = fileKey(file.getFileName().toString().replaceFirst("(?i)\\.lrc$", ""));
                if (name.equals(cleanTitle) || name.equals(cleanArtist + " " + cleanTitle)
                        || name.equals(cleanTitle + " " + cleanArtist)
                        || name.contains(cleanTitle) && !cleanTitle.isBlank() && name.contains(cleanArtist)) {
                    String raw = Files.readString(file, StandardCharsets.UTF_8);
                    var lines = LrcParser.parse(raw);
                    if (!lines.isEmpty()) return Lyrics.synced(lines);
                }
            }
        } catch (IOException e) {
            LOGGER.warn("[Music MC] Local lyrics lookup failed: {}", e.getMessage());
        }
        return null;
    }

    private static String fileKey(String value) {
        if (value == null) return "";
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT)
                .replaceAll("(?i)\\.lrc$", "")
                .replaceAll("[^a-z0-9]+", " ").trim();
        return normalized.replaceAll("\\s+", " ");
    }

    // ----------------------------------------------------------------------------------------
    // Matching helpers (package-private static for unit tests)
    // ----------------------------------------------------------------------------------------

    static Lyrics toLyrics(JsonObject o) {
        if (o.has("instrumental") && !o.get("instrumental").isJsonNull() && o.get("instrumental").getAsBoolean()) {
            return Lyrics.instrumentalTrack();
        }
        String synced = str(o, "syncedLyrics");
        if (synced != null && !synced.isBlank()) {
            var lines = LrcParser.parse(synced);
            if (!lines.isEmpty()) return Lyrics.synced(lines);
        }
        String plain = str(o, "plainLyrics");
        if (plain != null && !plain.isBlank()) {
            Lyrics l = Lyrics.plain(plain);
            if (!l.isEmpty()) return l;
        }
        return null;
    }

    /**
     * Scores search results: the title must match, then prefer synced lyrics, a matching artist
     * and the closest duration.
     */
    static JsonObject pickBest(JsonArray results, String title, String artist, long durationMs) {
        String wantTitle = normalize(title);
        String wantTitleClean = normalize(cleanTitle(title));
        String wantArtist = normalize(primaryArtist(artist));
        JsonObject best = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (JsonElement el : results) {
            if (!el.isJsonObject()) continue;
            JsonObject r = el.getAsJsonObject();
            String name = normalize(firstNonNull(str(r, "trackName"), str(r, "name")));
            String candArtist = normalize(str(r, "artistName"));

            double score = 0;
            if (name.equals(wantTitle) || name.equals(wantTitleClean)) {
                score += 50;
            } else if (!wantTitleClean.isEmpty() && (name.contains(wantTitleClean) || wantTitleClean.contains(name)) && !name.isEmpty()) {
                score += 20;
            } else {
                continue; // different song
            }

            if (!wantArtist.isEmpty() && !candArtist.isEmpty()) {
                if (candArtist.contains(wantArtist) || wantArtist.contains(candArtist)) {
                    score += 30;
                } else if (durationMs > 0) {
                    score -= 40; // maybe a cover; only accept if the duration also lines up
                } else {
                    continue;
                }
            }

            if (durationMs > 0 && r.has("duration") && !r.get("duration").isJsonNull()) {
                double diffSec = Math.abs(r.get("duration").getAsDouble() - durationMs / 1000.0);
                if (diffSec > 8) continue; // probably a different version/edit
                score += Math.max(0, 20 - diffSec * 5);
            }

            boolean instrumental = r.has("instrumental") && !r.get("instrumental").isJsonNull() && r.get("instrumental").getAsBoolean();
            String synced = str(r, "syncedLyrics");
            String plain = str(r, "plainLyrics");
            if (synced != null && !synced.isBlank()) score += 100;
            else if (plain != null && !plain.isBlank()) score += 40;
            else if (instrumental) score += 30;
            else continue; // empty entry

            if (score > bestScore) {
                bestScore = score;
                best = r;
            }
        }
        return bestScore >= 60 ? best : null;
    }

    private static final Pattern BRACKETED = Pattern.compile("\\s*[(\\[][^)\\]]*[)\\]]");
    private static final Pattern DASH_SUFFIX = Pattern.compile(
            "\\s+-\\s+.*\\b(remaster(ed)?|live|version|edit|mix|remix|mono|stereo|deluxe|bonus|radio|acoustic|demo|single)\\b.*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ARTIST_SPLIT = Pattern.compile(
            "\\s*(,|&|;|\\s+feat\\.?\\s+|\\s+ft\\.?\\s+|\\s+featuring\\s+|\\s+x\\s+|\\s+with\\s+)\\s*",
            Pattern.CASE_INSENSITIVE);

    static String cleanTitle(String title) {
        if (title == null) return "";
        String t = DASH_SUFFIX.matcher(title).replaceAll("");
        t = BRACKETED.matcher(t).replaceAll("");
        t = t.strip();
        return t.isEmpty() ? title.strip() : t;
    }

    static String primaryArtist(String artist) {
        if (artist == null) return "";
        String[] parts = ARTIST_SPLIT.split(artist.strip(), 2);
        return parts.length > 0 && !parts[0].isBlank() ? parts[0].strip() : artist.strip();
    }

    static String normalize(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        n = n.toLowerCase(Locale.ROOT).replace('’', '\'').replace("&", "and");
        n = n.replaceAll("[^\\p{L}\\p{N}' ]+", " ").replaceAll("\\s+", " ").strip();
        return n;
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
