package com.spotifymc.lyrics;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class LyricsMatchingTest {

    @Test
    public void cleansTitles() {
        assertEquals("Come Together", LyricsService.cleanTitle("Come Together - Remastered 2009"));
        assertEquals("Starboy", LyricsService.cleanTitle("Starboy (feat. Daft Punk)"));
        assertEquals("Song", LyricsService.cleanTitle("Song [Live]"));
        // Don't strip a dash that is part of the actual title
        assertEquals("Run - Boy Run", LyricsService.cleanTitle("Run - Boy Run"));
    }

    @Test
    public void findsPrimaryArtist() {
        assertEquals("The Weeknd", LyricsService.primaryArtist("The Weeknd, Daft Punk"));
        assertEquals("Simon", LyricsService.primaryArtist("Simon & Garfunkel"));
        assertEquals("Drake", LyricsService.primaryArtist("Drake feat. Rihanna"));
        assertEquals("Solo", LyricsService.primaryArtist("Solo"));
    }

    @Test
    public void normalizesForComparison() {
        assertEquals("beyonce", LyricsService.normalize("Beyoncé"));
        assertEquals("don't stop me now", LyricsService.normalize("Don’t Stop Me Now!"));
    }

    @Test
    public void prefersSyncedLyricsWithClosestDuration() {
        JsonArray results = JsonParser.parseString("""
                [
                  {"id":1,"trackName":"Get Lucky","artistName":"Daft Punk","duration":369,"plainLyrics":"x","syncedLyrics":null},
                  {"id":2,"trackName":"Get Lucky","artistName":"Daft Punk","duration":248,"plainLyrics":"x","syncedLyrics":"[00:01.00]x"},
                  {"id":3,"trackName":"Get Lucky","artistName":"Daft Punk","duration":369,"plainLyrics":"x","syncedLyrics":"[00:01.00]x"}
                ]""").getAsJsonArray();
        JsonObject best = LyricsService.pickBest(results, "Get Lucky", "Daft Punk, Pharrell Williams", 369_000);
        assertNotNull(best);
        assertEquals(3, best.get("id").getAsInt());
    }

    @Test
    public void rejectsDifferentSongs() {
        JsonArray results = JsonParser.parseString("""
                [{"id":1,"trackName":"Something Else","artistName":"Daft Punk","duration":369,"syncedLyrics":"[00:01.00]x"}]
                """).getAsJsonArray();
        assertNull(LyricsService.pickBest(results, "Get Lucky", "Daft Punk", 369_000));
    }

    @Test
    public void matchesCleanedTitle() {
        JsonArray results = JsonParser.parseString("""
                [{"id":7,"trackName":"Come Together","artistName":"The Beatles","duration":259.9,"syncedLyrics":"[00:01.00]x"}]
                """).getAsJsonArray();
        JsonObject best = LyricsService.pickBest(results, "Come Together - Remastered 2009", "The Beatles", 259_000);
        assertNotNull(best);
        assertEquals(7, best.get("id").getAsInt());
    }

    @Test
    public void convertsInstrumentalAndPlain() {
        Lyrics instrumental = LyricsService.toLyrics(JsonParser.parseString("{\"instrumental\":true}").getAsJsonObject());
        assertTrue(instrumental.instrumental());

        Lyrics plain = LyricsService.toLyrics(JsonParser.parseString(
                "{\"instrumental\":false,\"plainLyrics\":\"a\\nb\",\"syncedLyrics\":null}").getAsJsonObject());
        assertFalse(plain.synced());
        assertEquals(2, plain.lines().size());

        assertNull(LyricsService.toLyrics(JsonParser.parseString("{\"instrumental\":false}").getAsJsonObject()));
    }
}
