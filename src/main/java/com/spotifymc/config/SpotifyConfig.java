package com.spotifymc.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.spotifymc.media.PreferredApp;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Stores all user configuration options for Music MC.
 */
public class SpotifyConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public enum Anchor {
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT
    }

    /** Where playback info comes from. */
    public enum SourceMode {
        /** Read the OS media session: no login, works with Spotify, Apple Music and more. */
        SYSTEM("System Media (no login)"),
        /** Spotify Web API with the user's own developer Client ID (advanced). */
        SPOTIFY_API("Spotify Web API");

        private final String label;

        SourceMode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum LyricsStyle {
        /** A panel attached to the now-playing card. */
        ATTACHED("Attached to HUD"),
        /** Centered subtitles above the hotbar. */
        SUBTITLES("Subtitles"),
        /** Large cinematic lyric overlay. */
        CINEMATIC("Cinematic"),
        /** Floating lyric lines near the top of the game view. */
        SKY("Sky Lyrics");

        private final String label;

        LyricsStyle(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private String clientId = "YOUR_SPOTIFY_CLIENT_ID";
    private boolean hudEnabled = true;
    private boolean showAlbumArt = true;
    private boolean autoHideWhenInactive = false;
    private Anchor anchor = Anchor.TOP_LEFT;
    private int offsetX = 10;
    private int offsetY = 10;
    private float hudScale = 1.0f;
    private float hudOpacity = 0.85f;
    private int pollingIntervalSec = 2;
    private int accentColor = 0xFF1DB954; // Spotify Green

    // Music source (null until first run so existing Spotify API users can be migrated)
    private SourceMode sourceMode = null;
    private PreferredApp preferredApp = PreferredApp.ANY;
    private boolean matchAppColor = true;
    private boolean onlineArtworkLookup = true;

    // Lyrics
    private boolean lyricsEnabled = true;
    private LyricsStyle lyricsStyle = LyricsStyle.ATTACHED;
    private int lyricsUpcomingLines = 1;
    private int lyricsOffsetMs = 0;
    private float lyricsScale = 1.0f;

    private transient Path configFile;

    public SpotifyConfig() {
        Path configDir;
        try {
            if (FabricLoader.getInstance() != null && FabricLoader.getInstance().getConfigDir() != null) {
                configDir = FabricLoader.getInstance().getConfigDir().resolve("spotifymc");
            } else {
                configDir = Path.of(System.getProperty("java.io.tmpdir"), "spotifymc-test");
            }
        } catch (Throwable t) {
            configDir = Path.of(System.getProperty("java.io.tmpdir"), "spotifymc-test");
        }
        try {
            Files.createDirectories(configDir);
        } catch (Exception ignored) {
        }
        this.configFile = configDir.resolve("config.json");
    }

    public static SpotifyConfig load() {
        SpotifyConfig config = new SpotifyConfig();
        File file = config.configFile.toFile();
        if (file.exists()) {
            try (FileReader reader = new FileReader(file, StandardCharsets.UTF_8)) {
                SpotifyConfig loaded = GSON.fromJson(reader, SpotifyConfig.class);
                if (loaded != null) {
                    loaded.configFile = config.configFile;
                    return loaded;
                }
            } catch (Exception e) {
                System.err.println("[MusicMC] Failed to load config.json: " + e.getMessage());
            }
        }
        config.save();
        return config;
    }

    public synchronized void save() {
        try (FileWriter writer = new FileWriter(configFile.toFile(), StandardCharsets.UTF_8)) {
            GSON.toJson(this, writer);
        } catch (Exception e) {
            System.err.println("[MusicMC] Failed to save config.json: " + e.getMessage());
        }
    }

    // Getters and Setters
    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId != null ? clientId.trim() : "";
    }

    public boolean isHudEnabled() {
        return hudEnabled;
    }

    public void setHudEnabled(boolean hudEnabled) {
        this.hudEnabled = hudEnabled;
    }

    public boolean isShowAlbumArt() {
        return showAlbumArt;
    }

    public void setShowAlbumArt(boolean showAlbumArt) {
        this.showAlbumArt = showAlbumArt;
    }

    public boolean isAutoHideWhenInactive() {
        return autoHideWhenInactive;
    }

    public void setAutoHideWhenInactive(boolean autoHideWhenInactive) {
        this.autoHideWhenInactive = autoHideWhenInactive;
    }

    public Anchor getAnchor() {
        return anchor;
    }

    public void setAnchor(Anchor anchor) {
        this.anchor = anchor != null ? anchor : Anchor.TOP_LEFT;
    }

    public int getOffsetX() {
        return offsetX;
    }

    public void setOffsetX(int offsetX) {
        this.offsetX = offsetX;
    }

    public int getOffsetY() {
        return offsetY;
    }

    public void setOffsetY(int offsetY) {
        this.offsetY = offsetY;
    }

    public float getHudScale() {
        return hudScale;
    }

    public void setHudScale(float hudScale) {
        this.hudScale = Math.clamp(hudScale, 0.5f, 2.0f);
    }

    public float getHudOpacity() {
        return hudOpacity;
    }

    public void setHudOpacity(float hudOpacity) {
        this.hudOpacity = Math.clamp(hudOpacity, 0.2f, 1.0f);
    }

    public int getPollingIntervalSec() {
        return pollingIntervalSec;
    }

    public void setPollingIntervalSec(int pollingIntervalSec) {
        this.pollingIntervalSec = Math.clamp(pollingIntervalSec, 1, 10);
    }

    public int getAccentColor() {
        return accentColor;
    }

    public void setAccentColor(int accentColor) {
        this.accentColor = accentColor;
    }

    public boolean hasSourceMode() {
        return sourceMode != null;
    }

    public SourceMode getSourceMode() {
        return sourceMode != null ? sourceMode : SourceMode.SYSTEM;
    }

    public void setSourceMode(SourceMode sourceMode) {
        this.sourceMode = sourceMode != null ? sourceMode : SourceMode.SYSTEM;
    }

    public PreferredApp getPreferredApp() {
        return preferredApp != null ? preferredApp : PreferredApp.ANY;
    }

    public void setPreferredApp(PreferredApp preferredApp) {
        this.preferredApp = preferredApp != null ? preferredApp : PreferredApp.ANY;
    }

    public boolean isMatchAppColor() {
        return matchAppColor;
    }

    public void setMatchAppColor(boolean matchAppColor) {
        this.matchAppColor = matchAppColor;
    }

    public boolean isOnlineArtworkLookup() {
        return onlineArtworkLookup;
    }

    public void setOnlineArtworkLookup(boolean onlineArtworkLookup) {
        this.onlineArtworkLookup = onlineArtworkLookup;
    }

    public boolean isLyricsEnabled() {
        return lyricsEnabled;
    }

    public void setLyricsEnabled(boolean lyricsEnabled) {
        this.lyricsEnabled = lyricsEnabled;
    }

    public LyricsStyle getLyricsStyle() {
        return lyricsStyle != null ? lyricsStyle : LyricsStyle.ATTACHED;
    }

    public void setLyricsStyle(LyricsStyle lyricsStyle) {
        this.lyricsStyle = lyricsStyle != null ? lyricsStyle : LyricsStyle.ATTACHED;
    }

    public int getLyricsUpcomingLines() {
        return Math.clamp(lyricsUpcomingLines, 0, 3);
    }

    public void setLyricsUpcomingLines(int lines) {
        this.lyricsUpcomingLines = Math.clamp(lines, 0, 3);
    }

    /**
     * Positive values show lyrics earlier, negative later.
     */
    public int getLyricsOffsetMs() {
        return Math.clamp(lyricsOffsetMs, -5000, 5000);
    }

    public void setLyricsOffsetMs(int offsetMs) {
        this.lyricsOffsetMs = Math.clamp(offsetMs, -5000, 5000);
    }

    public float getLyricsScale() {
        return Math.clamp(lyricsScale, 0.5f, 2.0f);
    }

    public void setLyricsScale(float lyricsScale) {
        this.lyricsScale = Math.clamp(lyricsScale, 0.5f, 2.0f);
    }

    /**
     * Folder for helper files (e.g. the Windows media bridge script).
     */
    public Path getConfigDir() {
        return configFile.getParent();
    }
}
