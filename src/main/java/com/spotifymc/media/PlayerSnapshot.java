package com.spotifymc.media;

/**
 * One media player as reported by the operating system.
 *
 * @param appId         OS identifier for the player (used to send commands back to it)
 * @param playing       true if currently playing
 * @param paused        true if paused (a player that is neither is stopped/closed)
 * @param durationMs    track length, or 0 if the player doesn't report one
 * @param positionMs    position at the time of the poll (already extrapolated by the bridge if needed)
 * @param positionKnown false if the player doesn't expose a timeline; position is then tracked locally
 * @param artUrl        http(s) or file: URL of the cover art, or null
 * @param volumePercent 0-100, or -1 if the player's volume can't be read
 * @param systemCurrent true if the OS considers this the "current" media session
 */
public record PlayerSnapshot(
        String appId,
        boolean playing,
        boolean paused,
        String title,
        String artist,
        String album,
        long durationMs,
        long positionMs,
        boolean positionKnown,
        String artUrl,
        int volumePercent,
        boolean systemCurrent
) {
    public AppKind kind() {
        return AppKind.classify(appId);
    }

    public boolean hasTrack() {
        return (playing || paused) && title != null && !title.isBlank();
    }
}
