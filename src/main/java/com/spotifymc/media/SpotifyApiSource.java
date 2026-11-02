package com.spotifymc.media;

import com.spotifymc.api.SpotifyApiClient;
import com.spotifymc.api.SpotifyPlaybackState;
import com.spotifymc.auth.SpotifyAuthManager;

import java.util.concurrent.CompletableFuture;

/**
 * The original Spotify Web API integration, kept as an optional "advanced" source for people
 * who have their own Spotify developer app. Works with any Spotify device (phone, speaker...),
 * not just the computer Minecraft runs on.
 */
public class SpotifyApiSource implements MediaSource {

    private final SpotifyAuthManager authManager;
    private final SpotifyApiClient apiClient;

    public SpotifyApiSource(SpotifyAuthManager authManager, SpotifyApiClient apiClient) {
        this.authManager = authManager;
        this.apiClient = apiClient;
    }

    public boolean isAuthenticated() {
        return authManager.isAuthenticated();
    }

    @Override
    public CompletableFuture<SpotifyPlaybackState> fetchState() {
        if (!authManager.isAuthenticated()) {
            return CompletableFuture.completedFuture(SpotifyPlaybackState.NOT_AUTHENTICATED);
        }
        return apiClient.getPlaybackState()
                .exceptionally(err -> SpotifyPlaybackState.UNAVAILABLE);
    }

    @Override
    public CompletableFuture<Void> togglePlayPause(boolean currentlyPlaying) {
        return currentlyPlaying ? apiClient.pause() : apiClient.play();
    }

    @Override
    public CompletableFuture<Void> next() {
        return apiClient.next();
    }

    @Override
    public CompletableFuture<Void> previous() {
        return apiClient.previous();
    }

    @Override
    public boolean supportsVolume() {
        return true;
    }

    @Override
    public CompletableFuture<Void> setVolume(int percent) {
        return apiClient.setVolume(percent);
    }

    @Override
    public long nextPollDelayMs(SpotifyPlaybackState state, int configuredIntervalSec) {
        // Same adaptive back-off as before: Spotify rate-limits aggressive polling.
        return switch (state.status()) {
            case PLAYING -> configuredIntervalSec * 1000L;
            case PAUSED -> Math.max(configuredIntervalSec * 2L, 4L) * 1000L;
            case NOT_AUTHENTICATED -> 6_000L;
            default -> 8_000L;
        };
    }

    @Override
    public SpotifyPlaybackState initialState() {
        return authManager.isAuthenticated() ? SpotifyPlaybackState.NO_DEVICE : SpotifyPlaybackState.NOT_AUTHENTICATED;
    }
}
