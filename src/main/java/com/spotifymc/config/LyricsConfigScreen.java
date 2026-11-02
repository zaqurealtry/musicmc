package com.spotifymc.config;

import com.spotifymc.playback.SpotifyPlaybackManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Lyrics options: on/off, style, how many upcoming lines, timing offset and subtitle size.
 */
public class LyricsConfigScreen extends Screen {

    private static final int OFFSET_RANGE_MS = 3000;

    private final Screen parent;
    private final SpotifyConfig config;
    private final SpotifyPlaybackManager playbackManager;

    public LyricsConfigScreen(Screen parent, SpotifyConfig config, SpotifyPlaybackManager playbackManager) {
        super(Component.literal("Lyrics Settings"));
        this.parent = parent;
        this.config = config;
        this.playbackManager = playbackManager;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int colWidth = 150;
        int rowHeight = 24;
        int left = centerX - colWidth - 5;
        int right = centerX + 5;
        int y = 36;

        this.addRenderableWidget(Button.builder(getEnabledText(), btn -> {
            config.setLyricsEnabled(!config.isLyricsEnabled());
            config.save();
            btn.setMessage(getEnabledText());
            if (config.isLyricsEnabled()) {
                playbackManager.pollOnce();
            }
        }).bounds(left, y, colWidth, 20).build());

        this.addRenderableWidget(Button.builder(getStyleText(), btn -> {
            SpotifyConfig.LyricsStyle[] styles = SpotifyConfig.LyricsStyle.values();
            config.setLyricsStyle(styles[(config.getLyricsStyle().ordinal() + 1) % styles.length]);
            config.save();
            btn.setMessage(getStyleText());
        }).bounds(right, y, colWidth, 20).build());

        y += rowHeight;
        this.addRenderableWidget(new AbstractSliderButton(left, y, colWidth, 20, getUpcomingText(),
                config.getLyricsUpcomingLines() / 3.0) {
            @Override
            protected void updateMessage() {
                this.setMessage(getUpcomingText());
            }

            @Override
            protected void applyValue() {
                config.setLyricsUpcomingLines((int) Math.round(this.value * 3.0));
                config.save();
            }
        });

        double offsetProgress = (config.getLyricsOffsetMs() + OFFSET_RANGE_MS) / (2.0 * OFFSET_RANGE_MS);
        this.addRenderableWidget(new AbstractSliderButton(right, y, colWidth, 20, getOffsetText(),
                Math.clamp(offsetProgress, 0.0, 1.0)) {
            @Override
            protected void updateMessage() {
                this.setMessage(getOffsetText());
            }

            @Override
            protected void applyValue() {
                int ms = (int) Math.round((this.value * 2.0 - 1.0) * OFFSET_RANGE_MS / 50.0) * 50;
                config.setLyricsOffsetMs(ms);
                config.save();
            }
        });

        y += rowHeight;
        this.addRenderableWidget(new AbstractSliderButton(left, y, colWidth, 20, getScaleText(),
                (config.getLyricsScale() - 0.5f) / 1.5f) {
            @Override
            protected void updateMessage() {
                this.setMessage(getScaleText());
            }

            @Override
            protected void applyValue() {
                float val = 0.5f + (float) this.value * 1.5f;
                config.setLyricsScale(Math.round(val * 10.0f) / 10.0f);
                config.save();
            }
        });

        this.addRenderableWidget(Button.builder(getArtLookupText(), btn -> {
            config.setOnlineArtworkLookup(!config.isOnlineArtworkLookup());
            config.save();
            btn.setMessage(getArtLookupText());
        }).bounds(right, y, colWidth, 20).build());

        y += rowHeight;
        this.addRenderableWidget(Button.builder(Component.literal("Wrong lyrics? Fetch again"), btn -> {
            playbackManager.getLyricsService().clearCache();
            playbackManager.pollOnce();
            btn.active = false;
        }).bounds(centerX - 100, y, 200, 20).build());

        this.addRenderableWidget(Button.builder(Component.translatable("spotifymc.config.done"), btn -> this.onClose())
                .bounds(centerX - 100, this.height - 28, 200, 20).build());
    }

    private Component getEnabledText() {
        return Component.literal("Lyrics: " + (config.isLyricsEnabled() ? "§aON" : "§cOFF"));
    }

    private Component getStyleText() {
        return Component.literal("Style: §e" + config.getLyricsStyle().label());
    }

    private Component getUpcomingText() {
        return Component.literal("Upcoming Lines: " + config.getLyricsUpcomingLines());
    }

    private Component getOffsetText() {
        int ms = config.getLyricsOffsetMs();
        if (ms == 0) return Component.literal("Timing: in sync");
        return Component.literal(String.format("Timing: %+.2fs %s", ms / 1000.0, ms > 0 ? "earlier" : "later"));
    }

    private Component getScaleText() {
        return Component.literal(String.format("Subtitle Size: %.1fx", config.getLyricsScale()));
    }

    private Component getArtLookupText() {
        return Component.literal("Find Missing Art: " + (config.isOnlineArtworkLookup() ? "§aON" : "§cOFF"));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);
        int centerX = this.width / 2;
        context.centeredText(this.font, this.title, centerX, 12, 0xFFFFFFFF);
        context.centeredText(this.font, "Lyrics: lrclib.net  ·  Missing cover art: iTunes Search",
                centerX, this.height - 56, 0xFF888888);
        context.centeredText(this.font, "Both are free with no account. Only the song title and artist are sent.",
                centerX, this.height - 45, 0xFF888888);
    }

    @Override
    public void onClose() {
        config.save();
        if (this.minecraft != null && this.minecraft.gui != null) {
            this.minecraft.gui.setScreen(this.parent);
        }
    }
}
