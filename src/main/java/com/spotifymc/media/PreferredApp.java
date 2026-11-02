package com.spotifymc.media;

/**
 * Which app the no-login (system media) source should follow.
 */
public enum PreferredApp {
    ANY("Any App"),
    SPOTIFY("Spotify"),
    APPLE_MUSIC("Apple Music");

    private final String label;

    PreferredApp(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean matches(AppKind kind) {
        return switch (this) {
            case ANY -> true;
            case SPOTIFY -> kind == AppKind.SPOTIFY;
            case APPLE_MUSIC -> kind == AppKind.APPLE_MUSIC;
        };
    }
}
