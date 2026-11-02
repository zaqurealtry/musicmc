package com.spotifymc.media;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class SystemMediaSourceTest {

    private static PlayerSnapshot player(String app, boolean playing, boolean current) {
        return new PlayerSnapshot(app, playing, !playing, "Title " + app, "Artist", "Album",
                200_000, 1_000, true, null, -1, current);
    }

    @Test
    public void classifiesKnownApps() {
        assertEquals(AppKind.SPOTIFY, AppKind.classify("Spotify.exe"));
        assertEquals(AppKind.SPOTIFY, AppKind.classify("SpotifyAB.SpotifyMusic_zpdnekdrzrea0!Spotify"));
        assertEquals(AppKind.APPLE_MUSIC, AppKind.classify("AppleInc.AppleMusicWin_nzyj5cx40ttqa!App"));
        assertEquals(AppKind.APPLE_MUSIC, AppKind.classify("Music"));
        assertEquals(AppKind.APPLE_MUSIC, AppKind.classify("iTunes.exe"));
        assertEquals(AppKind.OTHER, AppKind.classify("chrome.exe"));
        assertEquals("Chrome", AppKind.friendlyName("chrome.exe"));
        assertEquals("Firefox", AppKind.friendlyName("firefox.instance1234"));
        assertEquals("Apple Music", AppKind.friendlyName("AppleInc.AppleMusicWin_nzyj5cx40ttqa!App"));
    }

    @Test
    public void prefersPlayingOverPaused() {
        PlayerSnapshot chosen = SystemMediaSource.choose(List.of(
                player("Spotify.exe", false, true),
                player("AppleInc.AppleMusicWin_x!App", true, false)
        ), PreferredApp.ANY, null);
        assertEquals("AppleInc.AppleMusicWin_x!App", chosen.appId());
    }

    @Test
    public void prefersMusicAppsOverBrowsers() {
        PlayerSnapshot chosen = SystemMediaSource.choose(List.of(
                player("chrome.exe", true, true),
                player("Spotify.exe", true, false)
        ), PreferredApp.ANY, null);
        assertEquals("Spotify.exe", chosen.appId());
    }

    @Test
    public void staysOnTheSamePlayer() {
        List<PlayerSnapshot> both = List.of(
                player("Spotify.exe", true, true),
                player("AppleInc.AppleMusicWin_x!App", true, false));
        assertEquals("AppleInc.AppleMusicWin_x!App",
                SystemMediaSource.choose(both, PreferredApp.ANY, "AppleInc.AppleMusicWin_x!App").appId());
    }

    @Test
    public void respectsPreferredApp() {
        List<PlayerSnapshot> both = List.of(
                player("Spotify.exe", true, true),
                player("AppleInc.AppleMusicWin_x!App", false, false));
        assertEquals("AppleInc.AppleMusicWin_x!App",
                SystemMediaSource.choose(both, PreferredApp.APPLE_MUSIC, null).appId());
        assertNull(SystemMediaSource.choose(List.of(player("chrome.exe", true, true)), PreferredApp.SPOTIFY, null));
    }

    @Test
    public void ignoresStoppedPlayers() {
        PlayerSnapshot stopped = new PlayerSnapshot("Spotify.exe", false, false, "T", "A", "B", 0, 0, false, null, -1, true);
        assertNull(SystemMediaSource.choose(List.of(stopped), PreferredApp.ANY, null));
    }

    @Test
    public void splitsAppleMusicWindowsArtistField() {
        PlayerSnapshot raw = new PlayerSnapshot("AppleInc.AppleMusicWin_x!App", true, false, "Song",
                "Taylor Swift — 1989 (Taylor's Version)", "", 0, 0, false, null, -1, true);
        PlayerSnapshot fixed = SystemMediaSource.normalize(raw);
        assertEquals("Taylor Swift", fixed.artist());
        assertEquals("1989 (Taylor's Version)", fixed.album());

        PlayerSnapshot spotify = player("Spotify.exe", true, true);
        assertSame(spotify, SystemMediaSource.normalize(spotify));
    }

    @Test
    public void localClockTracksPositionWithoutTimeline() {
        SystemMediaSource.LocalClock clock = new SystemMediaSource.LocalClock();
        assertEquals(0, clock.update("a", true, 1_000));
        assertEquals(4_000, clock.update("a", true, 5_000));
        assertEquals(5_000, clock.update("a", false, 6_000));   // paused at 5s
        assertEquals(5_000, clock.update("a", false, 60_000));  // stays paused
        assertEquals(5_000, clock.update("a", true, 61_000));   // resumed
        assertEquals(7_000, clock.update("a", true, 63_000));
        assertEquals(0, clock.update("b", true, 64_000));       // new track resets
    }
}
