package com.spotifymc.api;

/**
 * Represents a Spotify player target device.
 */
public record SpotifyDevice(
        String id,
        String name,
        String type,
        boolean isActive,
        int volumePercent
) {
}
