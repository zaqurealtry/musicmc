package com.spotifymc.lyrics;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class LrcParserTest {

    @Test
    public void parsesBasicLines() {
        List<Lyrics.Line> lines = LrcParser.parse("""
                [ar:Someone]
                [ti:Song]
                [00:12.34]First line
                [00:15.00]Second line
                [01:02.5]Third line
                """);
        assertEquals(3, lines.size());
        assertEquals(12_340, lines.get(0).timeMs());
        assertEquals("First line", lines.get(0).text());
        assertEquals(15_000, lines.get(1).timeMs());
        assertEquals(62_500, lines.get(2).timeMs());
    }

    @Test
    public void supportsMillisecondsAndNoFraction() {
        List<Lyrics.Line> lines = LrcParser.parse("[00:01.123]a\n[00:02]b\n[00:03:45]c");
        assertEquals(1_123, lines.get(0).timeMs());
        assertEquals(2_000, lines.get(1).timeMs());
        assertEquals(3_450, lines.get(2).timeMs());
    }

    @Test
    public void expandsRepeatedTimestampsAndSorts() {
        List<Lyrics.Line> lines = LrcParser.parse("[00:30.00][00:10.00]Chorus\n[00:20.00]Verse");
        assertEquals(3, lines.size());
        assertEquals("Chorus", lines.get(0).text());
        assertEquals(10_000, lines.get(0).timeMs());
        assertEquals("Verse", lines.get(1).text());
        assertEquals("Chorus", lines.get(2).text());
    }

    @Test
    public void appliesOffsetTag() {
        List<Lyrics.Line> lines = LrcParser.parse("[offset:+500]\n[00:10.00]Line");
        assertEquals(9_500, lines.get(0).timeMs());
        lines = LrcParser.parse("[offset:-250]\n[00:10.00]Line");
        assertEquals(10_250, lines.get(0).timeMs());
    }

    @Test
    public void stripsEnhancedWordTimingsAndKeepsBlankLines() {
        List<Lyrics.Line> lines = LrcParser.parse("[00:01.00]<00:01.00>Hello <00:01.50>world\n[00:05.00]");
        assertEquals("Hello world", lines.get(0).text());
        assertEquals("", lines.get(1).text());
    }

    @Test
    public void ignoresGarbage() {
        assertTrue(LrcParser.parse(null).isEmpty());
        assertTrue(LrcParser.parse("no timestamps here\njust text").isEmpty());
    }

    @Test
    public void indexAtFindsCurrentLine() {
        Lyrics lyrics = Lyrics.synced(LrcParser.parse("[00:10.00]a\n[00:20.00]b\n[00:30.00]c"));
        assertEquals(-1, lyrics.indexAt(5_000, 0));
        assertEquals(0, lyrics.indexAt(10_000, 0));
        assertEquals(0, lyrics.indexAt(19_999, 0));
        assertEquals(1, lyrics.indexAt(20_000, 0));
        assertEquals(2, lyrics.indexAt(999_999, 0));
    }

    @Test
    public void plainLyricsSpreadAcrossDuration() {
        Lyrics lyrics = Lyrics.plain("\none\ntwo\nthree\nfour\n\n");
        assertEquals(4, lyrics.lines().size());
        assertFalse(lyrics.synced());
        assertEquals(0, lyrics.indexAt(0, 100_000));
        assertEquals(2, lyrics.indexAt(50_000, 100_000));
        assertEquals(3, lyrics.indexAt(100_000, 100_000));
    }
}
