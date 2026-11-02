package com.spotifymc.config;

import com.spotifymc.SpotifyMod;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * ModMenu integration to provide an in-game settings screen button.
 */
public class ModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> new SpotifyConfigScreen(
                parent,
                SpotifyMod.getInstance().getConfig(),
                SpotifyMod.getInstance().getAuthManager(),
                SpotifyMod.getInstance().getPlaybackManager()
        );
    }
}
