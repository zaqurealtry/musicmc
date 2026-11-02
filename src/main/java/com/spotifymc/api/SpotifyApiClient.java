package com.spotifymc.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.spotifymc.SpotifyMod;
import com.spotifymc.auth.SpotifyAuthManager;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;

/**
 * Asynchronous HTTP client communicating with the Spotify Web API.
 * Handles rate limits, token refresh retries, and playback controls.
 */
public class SpotifyApiClient {

    private static final String BASE_URL = "https://api.spotify.com/v1/me/player";
    private final SpotifyAuthManager authManager;
    private final HttpClient httpClient;

    // Rate limiting
    private volatile long rateLimitUntilMs = 0L;

    public SpotifyApiClient(SpotifyAuthManager authManager) {
        this.authManager = authManager;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(6))
                .build();
    }

    /**
     * Checks if current requests are throttled due to HTTP 429.
     */
    public boolean isRateLimited() {
        return System.currentTimeMillis() < rateLimitUntilMs;
    }

    /**
     * Fetches current playback state asynchronously.
     */
    public CompletableFuture<SpotifyPlaybackState> getPlaybackState() {
        if (!authManager.isAuthenticated()) {
            return CompletableFuture.completedFuture(SpotifyPlaybackState.NOT_AUTHENTICATED);
        }

        if (isRateLimited()) {
            return CompletableFuture.completedFuture(SpotifyPlaybackState.UNAVAILABLE);
        }

        return executeWithRetry(token -> HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL))
                .header("Authorization", "Bearer " + token)
                .GET()
                .timeout(Duration.ofSeconds(6))
                .build()
        ).thenApply(response -> {
            int code = response.statusCode();
            if (code == 204) {
                // Playback inactive or no active player
                return SpotifyPlaybackState.NO_DEVICE;
            }
            if (code == 200) {
                return parsePlaybackJson(response.body());
            }
            if (code == 404) {
                return SpotifyPlaybackState.NO_DEVICE;
            }
            return SpotifyPlaybackState.UNAVAILABLE;
        }).exceptionally(err -> {
            System.err.println("[MusicMC] Failed to get playback state: " + err.getMessage());
            return SpotifyPlaybackState.UNAVAILABLE;
        });
    }

    /**
     * Resumes playback.
     */
    public CompletableFuture<Void> play() {
        return sendPlaybackCommand("PUT", BASE_URL + "/play");
    }

    /**
     * Pauses playback.
     */
    public CompletableFuture<Void> pause() {
        return sendPlaybackCommand("PUT", BASE_URL + "/pause");
    }

    /**
     * Skips to next track.
     */
    public CompletableFuture<Void> next() {
        return sendPlaybackCommand("POST", BASE_URL + "/next");
    }

    /**
     * Skips to previous track.
     */
    public CompletableFuture<Void> previous() {
        return sendPlaybackCommand("POST", BASE_URL + "/previous");
    }

    /**
     * Sets playback volume (0 to 100).
     */
    public CompletableFuture<Void> setVolume(int volumePercent) {
        int clamped = Math.clamp(volumePercent, 0, 100);
        return sendPlaybackCommand("PUT", BASE_URL + "/volume?volume_percent=" + clamped);
    }

    private CompletableFuture<Void> sendPlaybackCommand(String method, String url) {
        if (!authManager.isAuthenticated()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Not logged into Spotify."));
        }

        if (isRateLimited()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Spotify API is currently rate-limited."));
        }

        return executeWithRetry(token -> {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + token)
                    .timeout(Duration.ofSeconds(6));

            if ("PUT".equalsIgnoreCase(method)) {
                builder.PUT(HttpRequest.BodyPublishers.noBody());
            } else {
                builder.POST(HttpRequest.BodyPublishers.noBody());
            }

            return builder.build();
        }).thenAccept(response -> {
            int code = response.statusCode();
            if (code == 204 || code == 200) {
                // Success
                return;
            }
            if (code == 403) {
                throw new RuntimeException("PREMIUM_REQUIRED");
            }
            if (code == 404) {
                throw new RuntimeException("NO_ACTIVE_DEVICE");
            }
            throw new RuntimeException("Spotify API returned error code HTTP " + code);
        });
    }

    /**
     * Executes an HTTP request with automatic token refresh retry on 401 Unauthorized.
     */
    private CompletableFuture<HttpResponse<String>> executeWithRetry(java.util.function.Function<String, HttpRequest> requestFactory) {
        return authManager.getValidAccessToken().thenCompose(token -> {
            HttpRequest req = requestFactory.apply(token);
            return httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .thenCompose(response -> {
                        // Handle Rate Limiting (429)
                        if (response.statusCode() == 429) {
                            int retryAfterSec = response.headers().firstValue("Retry-After")
                                    .map(Integer::parseInt).orElse(5);
                            rateLimitUntilMs = System.currentTimeMillis() + (retryAfterSec * 1000L);
                            System.out.println("[MusicMC] Rate limited by Spotify. Retrying after " + retryAfterSec + " seconds.");
                            return CompletableFuture.completedFuture(response);
                        }

                        // Handle Token Expired (401)
                        if (response.statusCode() == 401) {
                            return authManager.refreshAccessToken().thenCompose(newToken -> {
                                HttpRequest retryReq = requestFactory.apply(newToken);
                                return httpClient.sendAsync(retryReq, HttpResponse.BodyHandlers.ofString());
                            });
                        }

                        return CompletableFuture.completedFuture(response);
                    });
        });
    }

    private SpotifyPlaybackState parsePlaybackJson(String jsonStr) {
        try {
            JsonObject root = JsonParser.parseString(jsonStr).getAsJsonObject();

            boolean isPlaying = root.has("is_playing") && root.get("is_playing").getAsBoolean();
            long progressMs = root.has("progress_ms") && !root.get("progress_ms").isJsonNull()
                    ? root.get("progress_ms").getAsLong() : 0L;

            SpotifyDevice device = null;
            if (root.has("device") && !root.get("device").isJsonNull()) {
                JsonObject devObj = root.getAsJsonObject("device");
                device = new SpotifyDevice(
                        devObj.has("id") && !devObj.get("id").isJsonNull() ? devObj.get("id").getAsString() : "",
                        devObj.has("name") ? devObj.get("name").getAsString() : "Unknown Device",
                        devObj.has("type") ? devObj.get("type").getAsString() : "Speaker",
                        devObj.has("is_active") && devObj.get("is_active").getAsBoolean(),
                        devObj.has("volume_percent") && !devObj.get("volume_percent").isJsonNull() ? devObj.get("volume_percent").getAsInt() : 100
                );
            }

            if (!root.has("item") || root.get("item").isJsonNull()) {
                // Item might be null if a podcast or ad is playing
                return new SpotifyPlaybackState(
                        isPlaying ? SpotifyPlaybackState.Status.PLAYING : SpotifyPlaybackState.Status.PAUSED,
                        "Advertisement / Podcast",
                        "Spotify",
                        "",
                        null,
                        progressMs,
                        0L,
                        System.currentTimeMillis(),
                        device
                );
            }

            JsonObject item = root.getAsJsonObject("item");
            String trackName = item.has("name") ? item.get("name").getAsString() : "Unknown Track";
            long durationMs = item.has("duration_ms") ? item.get("duration_ms").getAsLong() : 0L;

            // Artists
            StringJoiner artistsJoiner = new StringJoiner(", ");
            if (item.has("artists") && item.get("artists").isJsonArray()) {
                JsonArray artists = item.getAsJsonArray("artists");
                for (JsonElement a : artists) {
                    if (a.isJsonObject() && a.getAsJsonObject().has("name")) {
                        artistsJoiner.add(a.getAsJsonObject().get("name").getAsString());
                    }
                }
            }
            String artistName = artistsJoiner.length() > 0 ? artistsJoiner.toString() : "Unknown Artist";

            // Album & Art
            String albumName = "";
            String albumArtUrl = null;
            if (item.has("album") && !item.get("album").isJsonNull()) {
                JsonObject album = item.getAsJsonObject("album");
                if (album.has("name")) {
                    albumName = album.get("name").getAsString();
                }
                if (album.has("images") && album.get("images").isJsonArray()) {
                    JsonArray images = album.getAsJsonArray("images");
                    // Typically index 0 is 640x640, index 1 is 300x300, index 2 is 64x64
                    // Pick index 1 (medium) or 0
                    if (images.size() > 1 && images.get(1).isJsonObject()) {
                        albumArtUrl = images.get(1).getAsJsonObject().get("url").getAsString();
                    } else if (images.size() > 0 && images.get(0).isJsonObject()) {
                        albumArtUrl = images.get(0).getAsJsonObject().get("url").getAsString();
                    }
                }
            } else if (item.has("images") && item.get("images").isJsonArray()) {
                JsonArray images = item.getAsJsonArray("images");
                if (images.size() > 0 && images.get(0).isJsonObject()) {
                    albumArtUrl = images.get(0).getAsJsonObject().get("url").getAsString();
                }
            }

            SpotifyMod.LOGGER.info("[Music MC] Playback: '{}' by '{}' | Art URL: {}", trackName, artistName, albumArtUrl);

            return new SpotifyPlaybackState(
                    isPlaying ? SpotifyPlaybackState.Status.PLAYING : SpotifyPlaybackState.Status.PAUSED,
                    trackName,
                    artistName,
                    albumName,
                    albumArtUrl,
                    progressMs,
                    durationMs,
                    System.currentTimeMillis(),
                    device
            );

        } catch (Exception e) {
            System.err.println("[MusicMC] Failed to parse Spotify playback response: " + e.getMessage());
            return SpotifyPlaybackState.UNAVAILABLE;
        }
    }
}
