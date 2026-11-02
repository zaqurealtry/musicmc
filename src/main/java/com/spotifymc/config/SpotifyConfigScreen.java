package com.spotifymc.config;

import com.spotifymc.api.SpotifyPlaybackState;
import com.spotifymc.auth.SpotifyAuthManager;
import com.spotifymc.media.PreferredApp;
import com.spotifymc.playback.SpotifyPlaybackManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * In-game configuration screen: music source, HUD options and a link to lyrics settings.
 */
public class SpotifyConfigScreen extends Screen {

    private final Screen parent;
    private final SpotifyConfig config;
    private final SpotifyAuthManager authManager;
    private final SpotifyPlaybackManager playbackManager;

    private EditBox clientIdField;
    private Button connectButton;

    public SpotifyConfigScreen(Screen parent, SpotifyConfig config, SpotifyAuthManager authManager, SpotifyPlaybackManager playbackManager) {
        super(Component.translatable("spotifymc.config.title"));
        this.parent = parent;
        this.config = config;
        this.authManager = authManager;
        this.playbackManager = playbackManager;
    }

    private boolean isApiMode() {
        return config.getSourceMode() == SpotifyConfig.SourceMode.SPOTIFY_API;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int colWidth = 150;
        int rowHeight = 24;
        int left = centerX - colWidth - 5;
        int right = centerX + 5;

        this.clientIdField = null;
        this.connectButton = null;

        // 1. Music source + which app to follow
        int sourceY = 30;
        this.addRenderableWidget(Button.builder(getSourceText(), btn -> {
            SpotifyConfig.SourceMode[] modes = SpotifyConfig.SourceMode.values();
            config.setSourceMode(modes[(config.getSourceMode().ordinal() + 1) % modes.length]);
            config.save();
            playbackManager.onSourceSettingsChanged();
            this.rebuildWidgets();
        }).bounds(left, sourceY, colWidth, 20).build());

        Button appButton = Button.builder(getPreferredAppText(), btn -> {
            PreferredApp[] apps = PreferredApp.values();
            config.setPreferredApp(apps[(config.getPreferredApp().ordinal() + 1) % apps.length]);
            config.save();
            playbackManager.onSourceSettingsChanged();
            btn.setMessage(getPreferredAppText());
        }).bounds(right, sourceY, colWidth, 20).build();
        appButton.active = !isApiMode();
        this.addRenderableWidget(appButton);

        // 2. Spotify Web API login (advanced mode only)
        if (isApiMode()) {
            int apiY = 64;
            this.clientIdField = new EditBox(this.font, left, apiY, 196, 20, Component.translatable("spotifymc.config.client_id"));
            this.clientIdField.setMaxLength(64);
            this.clientIdField.setValue(config.getClientId());
            this.clientIdField.setResponder(text -> {
                config.setClientId(text);
                config.save();
            });
            this.addRenderableWidget(this.clientIdField);

            this.connectButton = Button.builder(
                    Component.translatable("spotifymc.config.btn_connect"),
                    btn -> {
                        if (authManager.isAuthenticated()) {
                            authManager.logout();
                            updateAuthButton();
                        } else {
                            config.setClientId(clientIdField.getValue());
                            config.save();
                            btn.active = false;
                            btn.setMessage(Component.literal("Opening browser..."));
                            authManager.startPkceLoginFlow().whenComplete((res, err) -> {
                                if (this.minecraft != null) {
                                    this.minecraft.execute(this::updateAuthButton);
                                }
                            });
                        }
                    }
            ).bounds(left + 200, apiY, colWidth * 2 + 10 - 200, 20).build();
            this.addRenderableWidget(this.connectButton);
            updateAuthButton();
        }

        // 3. HUD options
        int optionsY = 100;

        this.addRenderableWidget(Button.builder(getHudEnabledText(), btn -> {
            config.setHudEnabled(!config.isHudEnabled());
            config.save();
            btn.setMessage(getHudEnabledText());
        }).bounds(left, optionsY, colWidth, 20).build());

        this.addRenderableWidget(Button.builder(getAlbumArtText(), btn -> {
            config.setShowAlbumArt(!config.isShowAlbumArt());
            config.save();
            btn.setMessage(getAlbumArtText());
        }).bounds(right, optionsY, colWidth, 20).build());

        this.addRenderableWidget(Button.builder(getAutoHideText(), btn -> {
            config.setAutoHideWhenInactive(!config.isAutoHideWhenInactive());
            config.save();
            btn.setMessage(getAutoHideText());
        }).bounds(left, optionsY + rowHeight, colWidth, 20).build());

        this.addRenderableWidget(Button.builder(getAnchorText(), btn -> {
            SpotifyConfig.Anchor[] anchors = SpotifyConfig.Anchor.values();
            config.setAnchor(anchors[(config.getAnchor().ordinal() + 1) % anchors.length]);
            config.save();
            btn.setMessage(getAnchorText());
        }).bounds(right, optionsY + rowHeight, colWidth, 20).build());

        // Scale Slider (0.5x to 2.0x)
        int slidersY = optionsY + rowHeight * 2;
        double initialScaleProgress = (config.getHudScale() - 0.5f) / 1.5f;
        this.addRenderableWidget(new AbstractSliderButton(left, slidersY, colWidth, 20, getScaleText(), initialScaleProgress) {
            @Override
            protected void updateMessage() {
                this.setMessage(getScaleText());
            }

            @Override
            protected void applyValue() {
                float val = 0.5f + (float) this.value * 1.5f;
                config.setHudScale(Math.round(val * 10.0f) / 10.0f);
                config.save();
            }
        });

        // Opacity Slider (20% to 100%)
        double initialOpacityProgress = (config.getHudOpacity() - 0.2f) / 0.8f;
        this.addRenderableWidget(new AbstractSliderButton(right, slidersY, colWidth, 20, getOpacityText(), initialOpacityProgress) {
            @Override
            protected void updateMessage() {
                this.setMessage(getOpacityText());
            }

            @Override
            protected void applyValue() {
                float val = 0.2f + (float) this.value * 0.8f;
                config.setHudOpacity(Math.round(val * 100.0f) / 100.0f);
                config.save();
            }
        });

        // Row 4: poll interval (API only) or app colours, plus lyrics settings
        int row4Y = slidersY + rowHeight;
        if (isApiMode()) {
            double initialPollProgress = (config.getPollingIntervalSec() - 1.0) / 9.0;
            this.addRenderableWidget(new AbstractSliderButton(left, row4Y, colWidth, 20, getPollRateText(), initialPollProgress) {
                @Override
                protected void updateMessage() {
                    this.setMessage(getPollRateText());
                }

                @Override
                protected void applyValue() {
                    int sec = 1 + (int) Math.round(this.value * 9.0);
                    config.setPollingIntervalSec(sec);
                    config.save();
                }
            });
        } else {
            this.addRenderableWidget(Button.builder(getMatchColorText(), btn -> {
                config.setMatchAppColor(!config.isMatchAppColor());
                config.save();
                btn.setMessage(getMatchColorText());
            }).bounds(left, row4Y, colWidth, 20).build());
        }

        this.addRenderableWidget(Button.builder(Component.literal("Lyrics Settings..."), btn -> {
            if (this.minecraft != null) {
                this.minecraft.gui.setScreen(new LyricsConfigScreen(this, config, playbackManager));
            }
        }).bounds(right, row4Y, colWidth, 20).build());

        // Done button
        this.addRenderableWidget(Button.builder(
                Component.translatable("spotifymc.config.done"),
                btn -> this.onClose()
        ).bounds(centerX - 100, this.height - 28, 200, 20).build());
    }

    private void updateAuthButton() {
        if (connectButton == null) return;
        connectButton.active = true;
        if (authManager.isAuthenticated()) {
            connectButton.setMessage(Component.translatable("spotifymc.config.btn_disconnect"));
        } else if (authManager.isAuthenticating()) {
            connectButton.setMessage(Component.translatable("spotifymc.config.auth_status.waiting"));
            connectButton.active = false;
        } else {
            connectButton.setMessage(Component.translatable("spotifymc.config.btn_connect"));
        }
    }

    private Component getSourceText() {
        return Component.literal("Source: §e" + config.getSourceMode().label());
    }

    private Component getPreferredAppText() {
        return Component.literal("Follow: §e" + config.getPreferredApp().label());
    }

    private Component getHudEnabledText() {
        return Component.literal("HUD Overlay: " + (config.isHudEnabled() ? "§aON" : "§cOFF"));
    }

    private Component getAlbumArtText() {
        return Component.literal("Album Art: " + (config.isShowAlbumArt() ? "§aON" : "§cOFF"));
    }

    private Component getAutoHideText() {
        return Component.literal("Auto-Hide Paused: " + (config.isAutoHideWhenInactive() ? "§aON" : "§cOFF"));
    }

    private Component getAnchorText() {
        return Component.literal("Anchor: §e" + config.getAnchor().name());
    }

    private Component getMatchColorText() {
        return Component.literal("Match App Color: " + (config.isMatchAppColor() ? "§aON" : "§cOFF"));
    }

    private Component getScaleText() {
        return Component.literal(String.format("HUD Scale: %.1fx", config.getHudScale()));
    }

    private Component getOpacityText() {
        return Component.literal(String.format("Opacity: %d%%", Math.round(config.getHudOpacity() * 100.0f)));
    }

    private Component getPollRateText() {
        return Component.literal("API Poll Interval: " + config.getPollingIntervalSec() + "s");
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);

        int centerX = this.width / 2;
        context.centeredText(this.font, this.title, centerX, 12, 0xFFFFFFFF);

        if (isApiMode()) {
            context.text(this.font, "Spotify Developer Client ID (advanced):", centerX - 155, 54, 0xFFAAAAAA);

            String statusText;
            int statusColor;
            if (authManager.isAuthenticated()) {
                statusText = "● Connected to Spotify";
                statusColor = 0xFF1DB954;
            } else if (authManager.isAuthenticating()) {
                statusText = "○ Waiting for authorization...";
                statusColor = 0xFFFFD700;
            } else {
                statusText = "○ Not Connected";
                statusColor = 0xFFFF5555;
            }
            context.centeredText(this.font, statusText, centerX, 88, statusColor);
        } else {
            context.centeredText(this.font, "No login needed: shows whatever is playing on this computer.", centerX, 56, 0xFFAAAAAA);

            SpotifyPlaybackState state = playbackManager.getCurrentState();
            String statusText;
            int statusColor;
            if (state.hasTrack()) {
                statusText = "● Now showing: " + state.source();
                statusColor = 0xFF55FF55;
            } else if (state.status() == SpotifyPlaybackState.Status.UNAVAILABLE) {
                statusText = "✖ " + state.artistName();
                statusColor = 0xFFFF5555;
            } else {
                statusText = "○ Waiting for music. Press play in Spotify or Apple Music.";
                statusColor = 0xFFFFD700;
            }
            String shown = this.font.width(statusText) > this.width - 20
                    ? this.font.plainSubstrByWidth(statusText, this.width - 30) + "..."
                    : statusText;
            context.centeredText(this.font, shown, centerX, 72, statusColor);
        }
    }

    @Override
    public void onClose() {
        config.save();
        if (this.minecraft != null && this.minecraft.gui != null) {
            this.minecraft.gui.setScreen(this.parent);
        }
    }
}
