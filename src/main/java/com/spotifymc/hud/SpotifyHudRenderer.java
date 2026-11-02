package com.spotifymc.hud;

import com.spotifymc.api.SpotifyPlaybackState;
import com.spotifymc.config.SpotifyConfig;
import com.spotifymc.config.LyricsConfigScreen;
import com.spotifymc.config.SpotifyConfigScreen;
import com.spotifymc.media.AppKind;
import com.spotifymc.media.ArtworkLookup;
import com.spotifymc.playback.SpotifyPlaybackManager;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;

/**
 * Renders the in-game now-playing HUD overlay and lyrics.
 * Features glassmorphism style, live album artwork, smooth progress bar, and stays visible in chat.
 */
public class SpotifyHudRenderer implements HudElement {

    static final int HUD_WIDTH = 210;
    static final int HUD_HEIGHT = 50;
    private static final int ART_SIZE = 40;

    private final SpotifyConfig config;
    private final SpotifyPlaybackManager playbackManager;
    private final AlbumArtCache albumArtCache;
    private final ArtworkLookup artworkLookup;
    private final LyricsRenderer lyricsRenderer;

    public SpotifyHudRenderer(SpotifyConfig config, SpotifyPlaybackManager playbackManager, AlbumArtCache albumArtCache,
                              ArtworkLookup artworkLookup) {
        this.config = config;
        this.playbackManager = playbackManager;
        this.albumArtCache = albumArtCache;
        this.artworkLookup = artworkLookup;
        this.lyricsRenderer = new LyricsRenderer(config, playbackManager.getLyricsService());
    }

    public void register() {
        // Main HUD render hook
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("spotifymc", "hud"), this);

        // Explicit hook to ensure HUD stays visible when ChatScreen is open
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof ChatScreen) {
                ScreenEvents.afterExtract(screen).register((chatScreen, drawContext, mouseX, mouseY, tickDelta) -> {
                    renderHudOverlay(drawContext);
                });
            }
        });
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor drawContext, DeltaTracker deltaTracker) {
        Minecraft client = Minecraft.getInstance();

        // If in chat, ScreenEvents.afterExtract will render it on top of chat.
        // If other screens (pause menu, inventory) are open, do not draw unless it's the config screen.
        Screen currentScreen = client.gui != null ? client.gui.screen() : null;
        if (currentScreen instanceof ChatScreen) {
            return;
        }

        if (currentScreen != null && !isConfigScreen(currentScreen)) {
            return;
        }

        renderHudOverlay(drawContext);
    }

    public void renderHudOverlay(GuiGraphicsExtractor drawContext) {
        if (!config.isHudEnabled()) {
            return;
        }

        Minecraft client = Minecraft.getInstance();
        if (client.gui != null && client.gui.hud != null && client.gui.hud.isHidden()) {
            return;
        }

        SpotifyPlaybackState state = playbackManager.getCurrentState();
        Screen currentScreen = client.gui != null ? client.gui.screen() : null;

        // Auto-hide when inactive or paused if configured
        if (config.isAutoHideWhenInactive() && state.status() != SpotifyPlaybackState.Status.PLAYING) {
            return;
        }

        // If not authenticated and not config screen
        if (state.status() == SpotifyPlaybackState.Status.NOT_AUTHENTICATED && !isConfigScreen(currentScreen)) {
            return;
        }

        int screenWidth = drawContext.guiWidth();
        int screenHeight = drawContext.guiHeight();

        float scale = config.getHudScale();
        int originX = computeOriginX(screenWidth, scale);
        int originY = computeOriginY(screenHeight, scale);

        drawContext.pose().pushMatrix();
        drawContext.pose().scale(scale, scale);

        int renderX = (int) (originX / scale);
        int renderY = (int) (originY / scale);

        int accentCol = resolveAccent(state);
        int bgCol = ((int) (config.getHudOpacity() * 255.0f) << 24) | 0x121212;

        renderHudCard(drawContext, client.font, renderX, renderY, state, accentCol, bgCol);

        boolean lyricsOn = config.isLyricsEnabled() && state.hasTrack();
        if (lyricsOn && config.getLyricsStyle() == SpotifyConfig.LyricsStyle.ATTACHED) {
            boolean below = config.getAnchor() == SpotifyConfig.Anchor.TOP_LEFT
                    || config.getAnchor() == SpotifyConfig.Anchor.TOP_RIGHT;
            lyricsRenderer.renderAttached(drawContext, client.font, state, renderX, renderY, HUD_WIDTH, HUD_HEIGHT,
                    below, bgCol, accentCol);
        }

        drawContext.pose().popMatrix();

        if (lyricsOn) {
            switch (config.getLyricsStyle()) {
                case SUBTITLES -> lyricsRenderer.renderSubtitles(drawContext, client.font, state, screenWidth, screenHeight, config.getHudOpacity());
                case CINEMATIC -> lyricsRenderer.renderCinematic(drawContext, client.font, state, screenWidth, screenHeight, config.getHudOpacity());
                case SKY -> { /* rendered in world space by WorldLyricsRenderer */ }
                case ATTACHED -> { }
            }
        }
    }

    private boolean isConfigScreen(Screen screen) {
        return screen instanceof SpotifyConfigScreen || screen instanceof LyricsConfigScreen;
    }

    /**
     * Spotify green / Apple Music red when "match app color" is on, otherwise the configured accent.
     */
    private int resolveAccent(SpotifyPlaybackState state) {
        if (config.isMatchAppColor() && state.source() != null && !state.source().isBlank()) {
            AppKind kind = AppKind.classify(state.source());
            if (kind != AppKind.OTHER) return kind.accentColor();
        }
        return config.getAccentColor();
    }

    private void renderHudCard(GuiGraphicsExtractor context, Font font, int x, int y, SpotifyPlaybackState state,
                               int accentCol, int bgCol) {

        // Glassmorphism background card
        context.fill(x, y, x + HUD_WIDTH, y + HUD_HEIGHT, bgCol);

        // Left accent stripe (Spotify green / Apple Music red / custom)
        context.fill(x, y, x + 3, y + HUD_HEIGHT, accentCol);

        // Border outlines
        context.fill(x + 3, y, x + HUD_WIDTH, y + 1, 0x2AFFFFFF);
        context.fill(x + 3, y + HUD_HEIGHT - 1, x + HUD_WIDTH, y + HUD_HEIGHT, 0x1AFFFFFF);
        context.fill(x + HUD_WIDTH - 1, y, x + HUD_WIDTH, y + HUD_HEIGHT, 0x1AFFFFFF);

        int contentX = x + 6;
        int contentY = y + 5;

        // Album Artwork
        if (config.isShowAlbumArt()) {
            String artUrl = state.albumArtUrl();
            if ((artUrl == null || artUrl.isBlank()) && state.hasTrack() && config.isOnlineArtworkLookup()) {
                artUrl = artworkLookup.find(state.trackName(), state.artistName());
            }
            Identifier artId = albumArtCache.getOrFetchArtwork(artUrl);

            // Dark drop-shadow/sleeve border around cover
            context.fill(contentX - 1, contentY - 1, contentX + ART_SIZE + 1, contentY + ART_SIZE + 1, 0xFF000000);

            if (artId != null) {
                // Draw textured quad spanning the full ART_SIZE with proper (u0, u1, v0, v1) UV mapping
                context.blit(artId, contentX, contentY, contentX + ART_SIZE, contentY + ART_SIZE, 0.0f, 1.0f, 0.0f, 1.0f);
            } else {
                // Placeholder disc
                context.fill(contentX, contentY, contentX + ART_SIZE, contentY + ART_SIZE, 0xFF1C1C1C);
                context.centeredText(font, "♫", contentX + ART_SIZE / 2, contentY + 16, accentCol);
            }
            contentX += ART_SIZE + 6;
        }

        int maxTextWidth = (x + HUD_WIDTH - 6) - contentX;

        // Status Line
        String statusText;
        int statusColor;
        switch (state.status()) {
            case PLAYING -> {
                statusText = "▶ Playing";
                statusColor = accentCol;
            }
            case PAUSED -> {
                statusText = "⏸ Paused";
                statusColor = 0xFFAAAAAA;
            }
            case NO_ACTIVE_DEVICE -> {
                statusText = playbackManager.isUsingSpotifyApi() ? "⊘ No Player Active" : "⊘ Waiting for music";
                statusColor = 0xFFFFB300;
            }
            case NOT_AUTHENTICATED -> {
                statusText = "! Connect Spotify";
                statusColor = 0xFFFF5555;
            }
            default -> {
                statusText = "— Unavailable";
                statusColor = 0xFF888888;
            }
        }

        // Which app is playing, e.g. "▶ Playing · Apple Music"
        if (state.hasTrack() && state.source() != null && !state.source().isBlank()) {
            String withSource = statusText + " · " + state.source();
            if (font.width(withSource) <= maxTextWidth) {
                statusText = withSource;
            }
        }

        context.text(font, statusText, contentX, contentY, statusColor);

        // Track Name
        String trackName = truncateOrScroll(state.trackName(), font, maxTextWidth);
        context.text(font, trackName, contentX, contentY + 11, 0xFFFFFFFF);

        // Artist & Album Name
        String artistAndAlbum = state.artistName();
        if (state.albumName() != null && !state.albumName().isBlank() && !state.albumName().equals(state.trackName())) {
            artistAndAlbum += " • " + state.albumName();
        }
        String subText = truncateOrScroll(artistAndAlbum, font, maxTextWidth);
        context.text(font, subText, contentX, contentY + 22, 0xFF9E9E9E);

        // Progress Bar & Duration
        int barY = y + HUD_HEIGHT - 5;
        int barX = x + 6;
        int barWidth = HUD_WIDTH - 12;

        if (state.durationMs() > 0) {
            float progressRatio = state.getProgressPercentage();
            int filledWidth = (int) (barWidth * progressRatio);

            // Background bar track
            context.fill(barX, barY, barX + barWidth, barY + 2, 0x33FFFFFF);
            // Filled progress track
            context.fill(barX, barY, barX + filledWidth, barY + 2, accentCol);
        }
    }

    private String truncateOrScroll(String text, Font font, int maxWidth) {
        if (text == null || text.isBlank()) return "";
        if (font.width(text) <= maxWidth) {
            return text;
        }

        long time = System.currentTimeMillis() / 250;
        int scrollIndex = (int) (time % (text.length() + 8));
        String extended = text + "    " + text;

        if (scrollIndex < extended.length()) {
            String candidate = extended.substring(scrollIndex);
            return font.plainSubstrByWidth(candidate, maxWidth);
        }

        return font.plainSubstrByWidth(text, maxWidth);
    }

    public static String formatDuration(long millis) {
        long seconds = (millis / 1000) % 60;
        long minutes = (millis / (1000 * 60));
        return String.format("%d:%02d", minutes, seconds);
    }

    private int computeOriginX(int screenWidth, float scale) {
        int effectiveWidth = (int) (HUD_WIDTH * scale);
        return switch (config.getAnchor()) {
            case TOP_LEFT, BOTTOM_LEFT -> config.getOffsetX();
            case TOP_RIGHT, BOTTOM_RIGHT -> Math.max(0, screenWidth - effectiveWidth - config.getOffsetX());
        };
    }

    private int computeOriginY(int screenHeight, float scale) {
        int effectiveHeight = (int) (HUD_HEIGHT * scale);
        return switch (config.getAnchor()) {
            case TOP_LEFT, TOP_RIGHT -> config.getOffsetY();
            case BOTTOM_LEFT, BOTTOM_RIGHT -> Math.max(0, screenHeight - effectiveHeight - config.getOffsetY());
        };
    }
}
