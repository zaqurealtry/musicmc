package com.spotifymc.media;

import java.util.Locale;

/**
 * The music apps we recognise by name. Anything else (browsers, video players, etc.) is {@link #OTHER}.
 */
public enum AppKind {
    SPOTIFY("Spotify", 0xFF1DB954),
    APPLE_MUSIC("Apple Music", 0xFFFA2D48),
    OTHER("Media", 0xFF8AB4F8);

    private final String displayName;
    private final int accentColor;

    AppKind(String displayName, int accentColor) {
        this.displayName = displayName;
        this.accentColor = accentColor;
    }

    public String displayName() {
        return displayName;
    }

    public int accentColor() {
        return accentColor;
    }

    /**
     * Classifies an OS-level player identifier, e.g.
     * <ul>
     *     <li>Windows: {@code Spotify.exe}, {@code SpotifyAB.SpotifyMusic_zpdnekdrzrea0!Spotify},
     *     {@code AppleInc.AppleMusicWin_nzyj5cx40ttqa!App}, {@code iTunes.exe}</li>
     *     <li>macOS: {@code Spotify}, {@code Music}</li>
     *     <li>Linux (MPRIS): {@code spotify}, {@code cider}, {@code chromium.instance1234}</li>
     * </ul>
     */
    public static AppKind classify(String appId) {
        if (appId == null) return OTHER;
        String id = appId.toLowerCase(Locale.ROOT);
        if (id.contains("spotify")) return SPOTIFY;
        if (id.contains("applemusic") || id.contains("apple music") || id.contains("itunes")
                || id.equals("music") || id.startsWith("cider")) {
            return APPLE_MUSIC;
        }
        return OTHER;
    }

    /**
     * A friendly label for an unrecognised player id ("chrome.exe" -> "Chrome").
     */
    public static String friendlyName(String appId) {
        AppKind kind = classify(appId);
        if (kind != OTHER || appId == null || appId.isBlank()) {
            return kind.displayName;
        }
        String name = appId;
        int bang = name.indexOf('!');
        if (bang > 0) name = name.substring(0, bang);
        int underscore = name.indexOf('_');
        if (underscore > 0) name = name.substring(0, underscore);
        int lastDot = name.lastIndexOf('.');
        if (name.toLowerCase(Locale.ROOT).endsWith(".exe")) {
            name = name.substring(0, name.length() - 4);
        } else if (lastDot > 0 && lastDot < name.length() - 1 && name.chars().filter(c -> c == '.').count() >= 1) {
            // Package-style ids such as "Microsoft.ZuneMusic" or "chromium.instance123"
            String first = name.substring(0, name.indexOf('.'));
            String last = name.substring(lastDot + 1);
            name = last.startsWith("instance") ? first : last;
        }
        if (name.isEmpty()) return OTHER.displayName;
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
