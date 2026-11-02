package com.spotifymc;

import com.spotifymc.api.SpotifyApiClient;
import com.spotifymc.auth.SpotifyAuthManager;
import com.spotifymc.commands.SpotifyCommands;
import com.spotifymc.config.SpotifyConfig;
import com.spotifymc.hud.AlbumArtCache;
import com.spotifymc.hud.SpotifyHudRenderer;
import com.spotifymc.hud.WorldLyricsRenderer;
import com.spotifymc.keybinds.SpotifyKeybindHandler;
import com.spotifymc.lyrics.LyricsService;
import com.spotifymc.media.ArtworkLookup;
import com.spotifymc.media.SpotifyApiSource;
import com.spotifymc.media.SystemMediaSource;
import com.spotifymc.playback.SpotifyPlaybackManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main client mod initializer for Music MC.
 */
public class SpotifyMod implements ClientModInitializer {

    public static final String MOD_ID = "spotifymc";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static SpotifyMod instance;

    private SpotifyConfig config;
    private SpotifyAuthManager authManager;
    private SpotifyApiClient apiClient;
    private SpotifyPlaybackManager playbackManager;
    private AlbumArtCache albumArtCache;
    private ArtworkLookup artworkLookup;
    private SpotifyHudRenderer hudRenderer;
    private SpotifyKeybindHandler keybindHandler;

    @Override
    public void onInitializeClient() {
        instance = this;
        LOGGER.info("[Music MC] Initializing client-side music integration...");

        // Load configuration
        this.config = SpotifyConfig.load();

        // Core services
        this.authManager = new SpotifyAuthManager(this.config);
        this.apiClient = new SpotifyApiClient(this.authManager);

        // First run of this version: people who already linked the Spotify Web API keep using it,
        // everyone else starts on the no-login system media source.
        if (!this.config.hasSourceMode()) {
            this.config.setSourceMode(this.authManager.isAuthenticated()
                    ? SpotifyConfig.SourceMode.SPOTIFY_API
                    : SpotifyConfig.SourceMode.SYSTEM);
            this.config.save();
        }

        SpotifyApiSource apiSource = new SpotifyApiSource(this.authManager, this.apiClient);
        SystemMediaSource systemSource = new SystemMediaSource(this.config::getPreferredApp, this.config.getConfigDir());
        LyricsService lyricsService = new LyricsService(this.config::isLyricsEnabled, this.config.getConfigDir());
        this.playbackManager = new SpotifyPlaybackManager(this.config, this.authManager, apiSource, systemSource, lyricsService);
        this.albumArtCache = new AlbumArtCache();
        this.artworkLookup = new ArtworkLookup();

        // UI & Input
        this.hudRenderer = new SpotifyHudRenderer(this.config, this.playbackManager, this.albumArtCache, this.artworkLookup);
        this.hudRenderer.register();
        new WorldLyricsRenderer(this.config, this.playbackManager).register();

        this.keybindHandler = new SpotifyKeybindHandler(this.config, this.playbackManager);
        this.keybindHandler.register();

        // Chat commands
        SpotifyCommands.register(this.config, this.authManager, this.playbackManager);

        // Lifecycle cleanup on client shutdown
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            LOGGER.info("[Music MC] Shutting down background services...");
            if (this.playbackManager != null) {
                this.playbackManager.shutdown();
            }
            if (this.albumArtCache != null) {
                this.albumArtCache.clear();
            }
        });

        LOGGER.info("[Music MC] Successfully initialized.");
    }

    public static SpotifyMod getInstance() {
        return instance;
    }

    public SpotifyConfig getConfig() {
        return config;
    }

    public SpotifyAuthManager getAuthManager() {
        return authManager;
    }

    public SpotifyApiClient getApiClient() {
        return apiClient;
    }

    public SpotifyPlaybackManager getPlaybackManager() {
        return playbackManager;
    }

    public AlbumArtCache getAlbumArtCache() {
        return albumArtCache;
    }

    public SpotifyHudRenderer getHudRenderer() {
        return hudRenderer;
    }
}
