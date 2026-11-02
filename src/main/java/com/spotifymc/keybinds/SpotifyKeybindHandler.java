package com.spotifymc.keybinds;

import com.mojang.blaze3d.platform.InputConstants;
import com.spotifymc.config.SpotifyConfig;
import com.spotifymc.playback.SpotifyPlaybackManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/**
 * Registers and monitors keybinds for playback controls, HUD and lyrics toggles.
 */
public class SpotifyKeybindHandler {

    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath("spotifymc", "keys")
    );

    private final SpotifyConfig config;
    private final SpotifyPlaybackManager playbackManager;

    private KeyMapping playPauseKey;
    private KeyMapping nextTrackKey;
    private KeyMapping previousTrackKey;
    private KeyMapping toggleHudKey;
    private KeyMapping volumeUpKey;
    private KeyMapping volumeDownKey;
    private KeyMapping toggleLyricsKey;

    public SpotifyKeybindHandler(SpotifyConfig config, SpotifyPlaybackManager playbackManager) {
        this.config = config;
        this.playbackManager = playbackManager;
    }

    public void register() {
        int unbound = InputConstants.UNKNOWN.getValue();

        playPauseKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.spotifymc.play_pause",
                unbound,
                CATEGORY
        ));

        nextTrackKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.spotifymc.next",
                unbound,
                CATEGORY
        ));

        previousTrackKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.spotifymc.prev",
                unbound,
                CATEGORY
        ));

        toggleHudKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.spotifymc.toggle_hud",
                unbound,
                CATEGORY
        ));

        volumeUpKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.spotifymc.vol_up",
                unbound,
                CATEGORY
        ));

        volumeDownKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.spotifymc.vol_down",
                unbound,
                CATEGORY
        ));

        toggleLyricsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.spotifymc.toggle_lyrics",
                unbound,
                CATEGORY
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;

            while (playPauseKey.consumeClick()) {
                playbackManager.togglePlayPause();
            }

            while (nextTrackKey.consumeClick()) {
                playbackManager.nextTrack();
            }

            while (previousTrackKey.consumeClick()) {
                playbackManager.previousTrack();
            }

            while (volumeUpKey.consumeClick()) {
                playbackManager.adjustVolume(5);
            }

            while (volumeDownKey.consumeClick()) {
                playbackManager.adjustVolume(-5);
            }

            while (toggleLyricsKey.consumeClick()) {
                boolean on = !config.isLyricsEnabled();
                config.setLyricsEnabled(on);
                config.save();
                if (on) playbackManager.pollOnce();
                SpotifyPlaybackManager.sendFeedback(on ? "§a♫ Lyrics on" : "§7♫ Lyrics off", true);
            }

            while (toggleHudKey.consumeClick()) {
                boolean newState = !config.isHudEnabled();
                config.setHudEnabled(newState);
                config.save();
                SpotifyPlaybackManager.sendInGameMessage(
                        newState ? "§a[Music MC] HUD enabled" : "§e[Music MC] HUD disabled"
                );
            }
        });
    }
}
