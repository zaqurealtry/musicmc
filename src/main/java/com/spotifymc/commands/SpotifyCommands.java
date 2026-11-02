package com.spotifymc.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.spotifymc.api.SpotifyPlaybackState;
import com.spotifymc.auth.SpotifyAuthManager;
import com.spotifymc.config.SpotifyConfig;
import com.spotifymc.hud.SpotifyHudRenderer;
import com.spotifymc.media.PreferredApp;
import com.spotifymc.playback.SpotifyPlaybackManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * Registers client-side `/music` chat commands, plus `/spotify` as an alias.
 */
public class SpotifyCommands {

    public static void register(SpotifyConfig config, SpotifyAuthManager authManager, SpotifyPlaybackManager playbackManager) {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            var root = dispatcher.register(ClientCommands.literal("music")
                    // /music status
                    .then(ClientCommands.literal("status")
                            .executes(ctx -> {
                                SpotifyPlaybackState state = playbackManager.getCurrentState();
                                if (state.status() == SpotifyPlaybackState.Status.NOT_AUTHENTICATED) {
                                    ctx.getSource().sendFeedback(Component.literal("§e[Music MC] Not connected to Spotify. Run /music connect or switch to /music source system."));
                                    return 1;
                                }
                                if (state.status() == SpotifyPlaybackState.Status.UNAVAILABLE) {
                                    ctx.getSource().sendFeedback(Component.literal("§c[Music MC] Can't read your music player: " + state.artistName()));
                                    return 1;
                                }
                                if (!state.hasTrack()) {
                                    ctx.getSource().sendFeedback(Component.literal("§6[Music MC] Nothing is playing right now."));
                                    return 1;
                                }

                                String playIcon = state.status() == SpotifyPlaybackState.Status.PLAYING ? "▶" : "⏸";
                                String device = state.device() != null ? " (" + state.device().name() + ")" : "";
                                String progress = SpotifyHudRenderer.formatDuration(state.getExtrapolatedProgressMs())
                                        + (state.durationMs() > 0 ? " / " + SpotifyHudRenderer.formatDuration(state.durationMs()) : "");

                                ctx.getSource().sendFeedback(Component.literal(String.format(
                                        "§a[Music MC] %s §f%s §7by §f%s §7[%s]%s",
                                        playIcon, state.trackName(), state.artistName(), progress, device
                                )));
                                return 1;
                            })
                    )
                    // /music play
                    .then(ClientCommands.literal("play")
                            .executes(ctx -> {
                                if (playbackManager.getCurrentState().status() == SpotifyPlaybackState.Status.PLAYING) {
                                    ctx.getSource().sendFeedback(Component.literal("§7[Music MC] Already playing."));
                                } else {
                                    playbackManager.togglePlayPause();
                                    ctx.getSource().sendFeedback(Component.literal("§a[Music MC] Resuming playback..."));
                                }
                                return 1;
                            })
                    )
                    // /music pause
                    .then(ClientCommands.literal("pause")
                            .executes(ctx -> {
                                if (playbackManager.getCurrentState().status() == SpotifyPlaybackState.Status.PAUSED) {
                                    ctx.getSource().sendFeedback(Component.literal("§7[Music MC] Already paused."));
                                } else {
                                    playbackManager.togglePlayPause();
                                    ctx.getSource().sendFeedback(Component.literal("§e[Music MC] Pausing playback..."));
                                }
                                return 1;
                            })
                    )
                    // /music next
                    .then(ClientCommands.literal("next")
                            .executes(ctx -> {
                                playbackManager.nextTrack();
                                ctx.getSource().sendFeedback(Component.literal("§a[Music MC] Skipping to next track..."));
                                return 1;
                            })
                    )
                    // /music previous
                    .then(ClientCommands.literal("previous")
                            .executes(ctx -> {
                                playbackManager.previousTrack();
                                ctx.getSource().sendFeedback(Component.literal("§a[Music MC] Skipping to previous track..."));
                                return 1;
                            })
                    )
                    // /music hud
                    .then(ClientCommands.literal("hud")
                            .executes(ctx -> {
                                boolean newState = !config.isHudEnabled();
                                config.setHudEnabled(newState);
                                config.save();
                                ctx.getSource().sendFeedback(Component.literal(
                                        newState ? "§a[Music MC] HUD enabled." : "§e[Music MC] HUD disabled."
                                ));
                                return 1;
                            })
                    )
                    // /music lyrics [offset <ms> | style | refresh]
                    .then(ClientCommands.literal("lyrics")
                            .executes(ctx -> {
                                boolean on = !config.isLyricsEnabled();
                                config.setLyricsEnabled(on);
                                config.save();
                                if (on) playbackManager.pollOnce();
                                ctx.getSource().sendFeedback(Component.literal(
                                        on ? "§a[Music MC] Lyrics enabled." : "§e[Music MC] Lyrics disabled."));
                                return 1;
                            })
                            .then(ClientCommands.literal("style")
                                    .executes(ctx -> {
                                        SpotifyConfig.LyricsStyle[] styles = SpotifyConfig.LyricsStyle.values();
                                        config.setLyricsStyle(styles[(config.getLyricsStyle().ordinal() + 1) % styles.length]);
                                        config.save();
                                        ctx.getSource().sendFeedback(Component.literal(
                                                "§a[Music MC] Lyrics style: " + config.getLyricsStyle().label()));
                                        return 1;
                                    }))
                            .then(ClientCommands.literal("refresh")
                                    .executes(ctx -> {
                                        playbackManager.getLyricsService().clearCache();
                                        playbackManager.pollOnce();
                                        ctx.getSource().sendFeedback(Component.literal("§a[Music MC] Fetching lyrics again..."));
                                        return 1;
                                    }))
                            .then(ClientCommands.literal("offset")
                                    .then(ClientCommands.argument("milliseconds", IntegerArgumentType.integer(-5000, 5000))
                                            .executes(ctx -> {
                                                int ms = IntegerArgumentType.getInteger(ctx, "milliseconds");
                                                config.setLyricsOffsetMs(ms);
                                                config.save();
                                                ctx.getSource().sendFeedback(Component.literal(ms == 0
                                                        ? "§a[Music MC] Lyrics timing reset."
                                                        : "§a[Music MC] Lyrics will show " + Math.abs(ms) + "ms "
                                                        + (ms > 0 ? "earlier." : "later.")));
                                                return 1;
                                            })))
                    )
                    // /music source <system|spotify>
                    .then(ClientCommands.literal("source")
                            .then(ClientCommands.literal("system")
                                    .executes(ctx -> {
                                        config.setSourceMode(SpotifyConfig.SourceMode.SYSTEM);
                                        config.save();
                                        playbackManager.onSourceSettingsChanged();
                                        ctx.getSource().sendFeedback(Component.literal(
                                                "§a[Music MC] Now following whatever plays on this computer (Spotify, Apple Music, ...). No login needed."));
                                        return 1;
                                    }))
                            .then(ClientCommands.literal("spotify")
                                    .executes(ctx -> {
                                        config.setSourceMode(SpotifyConfig.SourceMode.SPOTIFY_API);
                                        config.save();
                                        playbackManager.onSourceSettingsChanged();
                                        ctx.getSource().sendFeedback(Component.literal(authManager.isAuthenticated()
                                                ? "§a[Music MC] Using the Spotify Web API."
                                                : "§e[Music MC] Using the Spotify Web API. Run /music connect to log in."));
                                        return 1;
                                    }))
                    )
                    // /music app <any|spotify|apple>
                    .then(ClientCommands.literal("app")
                            .then(ClientCommands.literal("any").executes(ctx -> setApp(ctx.getSource()::sendFeedback, config, playbackManager, PreferredApp.ANY)))
                            .then(ClientCommands.literal("spotify").executes(ctx -> setApp(ctx.getSource()::sendFeedback, config, playbackManager, PreferredApp.SPOTIFY)))
                            .then(ClientCommands.literal("apple").executes(ctx -> setApp(ctx.getSource()::sendFeedback, config, playbackManager, PreferredApp.APPLE_MUSIC)))
                    )
                    // /music connect
                    .then(ClientCommands.literal("connect")
                            .executes(ctx -> {
                                if (config.getSourceMode() != SpotifyConfig.SourceMode.SPOTIFY_API) {
                                    ctx.getSource().sendFeedback(Component.literal(
                                            "§7[Music MC] You don't need to connect anything: the mod already follows Spotify / Apple Music on this computer. "
                                                    + "To use the Spotify Web API instead (e.g. to control your phone), run /music source spotify first."));
                                    return 1;
                                }
                                ctx.getSource().sendFeedback(Component.literal("§a[Music MC] Opening browser for Spotify authorization..."));
                                authManager.startPkceLoginFlow().exceptionally(err -> {
                                    ctx.getSource().sendFeedback(Component.literal("§c[Music MC] Login failed: " + err.getMessage()));
                                    return null;
                                });
                                return 1;
                            })
                    )
                    // /music logout
                    .then(ClientCommands.literal("logout")
                            .executes(ctx -> {
                                authManager.logout();
                                ctx.getSource().sendFeedback(Component.literal("§e[Music MC] Successfully logged out and deleted credentials."));
                                return 1;
                            })
                    )
            );

            // /spotify still works exactly like /music (it was the command before 1.3.0).
            dispatcher.register(ClientCommands.literal("spotify").redirect(root));
        });
    }

    private static int setApp(Consumer<Component> feedback, SpotifyConfig config,
                              SpotifyPlaybackManager playbackManager, PreferredApp app) {
        config.setPreferredApp(app);
        config.save();
        playbackManager.onSourceSettingsChanged();
        feedback.accept(Component.literal("§a[Music MC] Following: " + app.label()));
        return 1;
    }
}
