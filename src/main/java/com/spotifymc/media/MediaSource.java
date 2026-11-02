package com.spotifymc.media;

import com.spotifymc.api.SpotifyPlaybackState;

import java.util.concurrent.CompletableFuture;

/**
 * Something that can tell us what's playing and control it: either the operating system's
 * media session (no login) or the Spotify Web API (optional, needs a Spotify developer app).
 */
public interface MediaSource {

    /**
     * Fetches the latest playback state. Never completes exceptionally; problems are reported as
     * an UNAVAILABLE / NOT_AUTHENTICATED state instead.
     */
    CompletableFuture<SpotifyPlaybackState> fetchState();

    /**
     * Toggles play/pause. {@code currentlyPlaying} is our best knowledge of the current state,
     * for sources that need an explicit play or pause call.
     */
    CompletableFuture<Void> togglePlayPause(boolean currentlyPlaying);

    CompletableFuture<Void> next();

    CompletableFuture<Void> previous();

    boolean supportsVolume();

    CompletableFuture<Void> setVolume(int percent);

    /**
     * How long to wait before the next poll, given the state we just received.
     */
    long nextPollDelayMs(SpotifyPlaybackState state, int configuredIntervalSec);

    /**
     * State to show right after switching to this source, before the first poll returns.
     */
    SpotifyPlaybackState initialState();

    /**
     * Called when the user switches away from this source; release helper processes etc.
     */
    default void deactivate() {
    }

    default void shutdown() {
        deactivate();
    }
}
