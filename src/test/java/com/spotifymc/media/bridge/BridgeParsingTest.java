package com.spotifymc.media.bridge;

import com.google.gson.JsonParser;
import com.spotifymc.media.PlayerSnapshot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class BridgeParsingTest {

    private static final String S = String.valueOf((char) 30);

    @Test
    public void parsesWindowsSessions() {
        List<PlayerSnapshot> players = WindowsSmtcBridge.parseSessions(JsonParser.parseString("""
                [{"app":"Spotify.exe","current":true,"status":"Playing","title":"Starboy","artist":"The Weeknd",
                  "album":"Starboy","durationMs":230000,"positionMs":61000,"positionKnown":true,"art":""},
                 {"app":"AppleInc.AppleMusicWin_nzyj5cx40ttqa!App","current":false,"status":"Paused","title":"x",
                  "artist":"y","album":"","durationMs":0,"positionMs":0,"positionKnown":false,"art":""}]
                """).getAsJsonArray());
        assertEquals(2, players.size());
        PlayerSnapshot s = players.get(0);
        assertTrue(s.playing());
        assertTrue(s.systemCurrent());
        assertEquals(230_000, s.durationMs());
        assertEquals(61_000, s.positionMs());
        assertNull(s.artUrl());
        assertTrue(players.get(1).paused());
        assertFalse(players.get(1).positionKnown());
    }

    @Test
    public void parsesSpotifyAppleScriptOutput() throws IOException {
        PlayerSnapshot p = MacScriptingBridge.parse("Spotify",
                "playing" + S + "Starboy" + S + "The Weeknd" + S + "Starboy" + S + "230453" + S + "61,5" + S
                        + "https://i.scdn.co/image/abc" + S + "80\n");
        assertNotNull(p);
        assertTrue(p.playing());
        assertEquals(230_453, p.durationMs());
        assertEquals(61_500, p.positionMs());
        assertEquals("https://i.scdn.co/image/abc", p.artUrl());
        assertEquals(80, p.volumePercent());
    }

    @Test
    public void parsesMusicAppleScriptOutput() throws IOException {
        PlayerSnapshot p = MacScriptingBridge.parse("Music",
                "paused" + S + "Song" + S + "Artist" + S + "Album" + S + "245.5" + S + "12.25" + S + "" + S + "100");
        assertNotNull(p);
        assertTrue(p.paused());
        assertEquals(245_500, p.durationMs());
        assertEquals(12_250, p.positionMs());
        assertNull(p.artUrl());
        assertNull(MacScriptingBridge.parse("Music", "stopped"));
    }

    @Test
    public void explainsMissingAutomationPermission() {
        IOException e = assertThrows(IOException.class, () ->
                MacScriptingBridge.parse("Music", "error" + S + "-1743" + S + "Not authorized"));
        assertTrue(e.getMessage().contains("Automation"));
    }

    @Test
    public void parsesPlayerctlOutput() {
        String out = "spotify" + S + "Playing" + S + "Song" + S + "Artist" + S + "Album" + S + "200000000" + S
                + "1500000" + S + "https://i.scdn.co/x" + S + "0.650000\n"
                + "firefox.instance42" + S + "Paused" + S + "Video" + S + "" + S + "" + S + "" + S + "0" + S + "" + S + "1.0\n";
        List<PlayerSnapshot> players = LinuxPlayerctlBridge.parse(out);
        assertEquals(2, players.size());
        assertEquals(200_000, players.get(0).durationMs());
        assertEquals(1_500, players.get(0).positionMs());
        assertEquals(65, players.get(0).volumePercent());
        assertTrue(players.get(0).systemCurrent());
        assertFalse(players.get(1).positionKnown());
    }
}
