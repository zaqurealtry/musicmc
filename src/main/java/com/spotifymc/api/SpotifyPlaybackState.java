package com.spotifymc.api;

/**
 * Immutable snapshot representing the current playback state, regardless of which
 * source (Spotify Web API or the operating system's media session) produced it.
 *
 * @param source human-readable name of the app that is playing (e.g. "Spotify", "Apple Music").
 */
public record SpotifyPlaybackState(
        Status status,
        String trackName,
        String artistName,
        String albumName,
        String albumArtUrl,
        long progressMs,
        long durationMs,
        long timestampMs,
        SpotifyDevice device,
        String source
) {

    public enum Status {
        PLAYING,
        PAUSED,
        NO_ACTIVE_DEVICE,
        NOT_AUTHENTICATED,
        UNAVAILABLE
    }

    public static final String SOURCE_SPOTIFY = "Spotify";

    /**
     * Backwards-compatible constructor used by the Spotify Web API client.
     */
    public SpotifyPlaybackState(Status status, String trackName, String artistName, String albumName,
                                String albumArtUrl, long progressMs, long durationMs, long timestampMs,
                                SpotifyDevice device) {
        this(status, trackName, artistName, albumName, albumArtUrl, progressMs, durationMs, timestampMs, device, SOURCE_SPOTIFY);
    }

    public static final SpotifyPlaybackState NOT_AUTHENTICATED = new SpotifyPlaybackState(
            Status.NOT_AUTHENTICATED,
            "Not Connected",
            "Connect in Mod Settings",
            "",
            null,
            0,
            0,
            System.currentTimeMillis(),
            null
    );

    public static final SpotifyPlaybackState NO_DEVICE = new SpotifyPlaybackState(
            Status.NO_ACTIVE_DEVICE,
            "No Active Player",
            "Open Spotify on any device",
            "",
            null,
            0,
            0,
            System.currentTimeMillis(),
            null
    );

    public static final SpotifyPlaybackState NOTHING_PLAYING = new SpotifyPlaybackState(
            Status.NO_ACTIVE_DEVICE,
            "Nothing Playing",
            "Play something in Spotify or Apple Music",
            "",
            null,
            0,
            0,
            System.currentTimeMillis(),
            null,
            ""
    );

    public static final SpotifyPlaybackState UNAVAILABLE = new SpotifyPlaybackState(
            Status.UNAVAILABLE,
            "Unavailable",
            "Offline or Rate Limited",
            "",
            null,
            0,
            0,
            System.currentTimeMillis(),
            null
    );

    /**
     * An UNAVAILABLE state carrying a human-readable reason (shown as the HUD's second line).
     */
    public static SpotifyPlaybackState unavailable(String reason) {
        return new SpotifyPlaybackState(Status.UNAVAILABLE, "Unavailable", reason, "", null,
                0, 0, System.currentTimeMillis(), null, "");
    }

    /**
     * Copy with a new status and progress anchor (used for optimistic UI updates after controls).
     */
    public SpotifyPlaybackState withPlayback(Status newStatus, long newProgressMs) {
        return new SpotifyPlaybackState(newStatus, trackName, artistName, albumName, albumArtUrl,
                newProgressMs, durationMs, System.currentTimeMillis(), device, source);
    }

    /**
     * Copy with a different device (used for optimistic volume updates).
     */
    public SpotifyPlaybackState withDevice(SpotifyDevice newDevice) {
        return new SpotifyPlaybackState(status, trackName, artistName, albumName, albumArtUrl,
                getExtrapolatedProgressMs(), durationMs, System.currentTimeMillis(), newDevice, source);
    }

    /**
     * True when there is an actual track loaded (playing or paused).
     */
    public boolean hasTrack() {
        return (status == Status.PLAYING || status == Status.PAUSED)
                && trackName != null && !trackName.isBlank();
    }

    /**
     * Calculates the estimated current playback position by extrapolating elapsed time
     * since the last poll. Prevents needing to poll every millisecond.
     */
    public long getExtrapolatedProgressMs() {
        if (status != Status.PLAYING) {
            return progressMs;
        }
        long elapsed = Math.max(0L, System.currentTimeMillis() - timestampMs);
        if (durationMs <= 0) {
            // Unknown duration (some players don't report a timeline): keep counting.
            return progressMs + elapsed;
        }
        return Math.min(progressMs + elapsed, durationMs);
    }

    public float getProgressPercentage() {
        if (durationMs <= 0) return 0f;
        return (float) getExtrapolatedProgressMs() / (float) durationMs;
    }
}
