package com.spotifymc.hud;

import com.spotifymc.api.SpotifyPlaybackState;
import com.spotifymc.config.SpotifyConfig;
import com.spotifymc.lyrics.Lyrics;
import com.spotifymc.lyrics.LyricsService;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the lyrics for the current track, either as a panel attached to the now-playing card
 * or as centered subtitles above the hotbar.
 */
public class LyricsRenderer {

    private static final int ROW_HEIGHT = 10;
    private static final int PADDING = 4;
    private static final int COLOR_CURRENT = 0xFFFFFFFF;
    private static final int COLOR_OTHER = 0xFF8C8C8C;
    private static final int COLOR_PAST = 0xFF6A6A6A;
    private static final String ELLIPSIS = "…";

    private final SpotifyConfig config;
    private final LyricsService lyricsService;

    public LyricsRenderer(SpotifyConfig config, LyricsService lyricsService) {
        this.config = config;
        this.lyricsService = lyricsService;
    }

    private record Row(String text, int color, boolean current) {
    }

    // ----------------------------------------------------------------------------------------
    // Attached panel (drawn in the HUD card's scaled coordinate space)
    // ----------------------------------------------------------------------------------------

    /**
     * @param cardX  left edge of the card (scaled space)
     * @param cardY  top edge of the card (scaled space)
     * @param below  true to draw under the card, false to draw above it
     */
    public void renderAttached(GuiGraphicsExtractor context, Font font, SpotifyPlaybackState state,
                               int cardX, int cardY, int cardWidth, int cardHeight, boolean below,
                               int bgColor, int accentColor) {
        LyricsService.Result result = lyricsService.resultFor(state);
        int textWidth = cardWidth - PADDING * 2 - 4;
        List<Row> rows = new ArrayList<>();

        switch (result.status()) {
            case IDLE -> {
                return;
            }
            case LOADING -> rows.add(new Row("♪ Finding lyrics...", COLOR_OTHER, false));
            case NOT_FOUND -> rows.add(new Row("No lyrics found for this song", COLOR_OTHER, false));
            case ERROR -> rows.add(new Row("Lyrics unavailable (offline?)", COLOR_OTHER, false));
            case FOUND -> {
                Lyrics lyrics = result.lyrics();
                if (lyrics.instrumental()) {
                    rows.add(new Row("♪ Instrumental ♪", COLOR_OTHER, false));
                    break;
                }
                int idx = currentIndex(lyrics, state);
                String prev = lyrics.textAt(idx - 1);
                rows.add(new Row(prev == null ? "" : truncate(font, display(prev), textWidth), COLOR_PAST, false));

                String current = idx < 0 ? "♪" : display(lyrics.textAt(idx));
                if (!lyrics.synced()) current = "≈ " + current;
                List<String> wrapped = wrap(font, current, textWidth, 2);
                rows.add(new Row(wrapped.get(0), COLOR_CURRENT, true));
                // Always reserve a second row for the current line so the panel doesn't jump around.
                rows.add(new Row(wrapped.size() > 1 ? wrapped.get(1) : "", COLOR_CURRENT, true));

                for (int i = 1; i <= config.getLyricsUpcomingLines(); i++) {
                    String next = lyrics.textAt(idx + i);
                    rows.add(new Row(next == null ? "" : truncate(font, display(next), textWidth), COLOR_OTHER, false));
                }
            }
        }

        int height = PADDING * 2 + rows.size() * ROW_HEIGHT - 1;
        int x = cardX;
        int y = below ? cardY + cardHeight + 2 : cardY - height - 2;

        context.fill(x, y, x + cardWidth, y + height, bgColor);
        context.fill(x, y, x + 3, y + height, accentColor);
        context.fill(x + 3, y, x + cardWidth, y + 1, 0x2AFFFFFF);

        int rowY = y + PADDING;
        boolean markerDrawn = false;
        for (Row row : rows) {
            if (row.current() && !markerDrawn && !row.text().isEmpty()) {
                context.fill(x + PADDING + 2, rowY - 1, x + PADDING + 3, rowY + 8, accentColor);
                markerDrawn = true;
            }
            if (!row.text().isEmpty()) {
                context.text(font, row.text(), x + PADDING + 6, rowY, row.color());
            }
            rowY += ROW_HEIGHT;
        }
    }

    // ----------------------------------------------------------------------------------------
    // Subtitles (screen space, centered above the hotbar)
    // ----------------------------------------------------------------------------------------

    public void renderSubtitles(GuiGraphicsExtractor context, Font font, SpotifyPlaybackState state,
                                int screenWidth, int screenHeight, float opacity) {
        LyricsService.Result result = lyricsService.resultFor(state);
        if (result.status() != LyricsService.Status.FOUND || result.lyrics().instrumental()) {
            return;
        }
        Lyrics lyrics = result.lyrics();
        int idx = currentIndex(lyrics, state);
        String current = idx < 0 ? null : display(lyrics.textAt(idx));
        String next = config.getLyricsUpcomingLines() > 0 ? lyrics.textAt(Math.max(0, idx + 1)) : null;
        if (idx < 0 && next == null) return;

        float scale = config.getLyricsScale();
        int sw = (int) (screenWidth / scale);
        int sh = (int) (screenHeight / scale);
        int maxWidth = Math.min(320, sw - 20);

        List<Row> rows = new ArrayList<>();
        if (current != null && !current.equals("♪")) {
            for (String line : wrap(font, current, maxWidth, 2)) {
                rows.add(new Row(line, COLOR_CURRENT, true));
            }
        }
        if (next != null && !next.isBlank()) {
            rows.add(new Row(truncate(font, next, maxWidth), COLOR_OTHER, false));
        }
        if (rows.isEmpty()) return;

        int bg = ((int) (Math.clamp(opacity, 0.2f, 1.0f) * 0.75f * 255.0f) << 24);

        context.pose().pushMatrix();
        context.pose().scale(scale, scale);

        // Sit above the action bar / XP bar area.
        int bottom = (int) ((screenHeight - 76) / scale);
        int y = bottom - rows.size() * (ROW_HEIGHT + 2);
        for (Row row : rows) {
            int w = font.width(row.text());
            int x = sw / 2 - w / 2;
            context.fill(x - 4, y - 2, x + w + 4, y + ROW_HEIGHT, bg);
            context.text(font, row.text(), x, y, row.color());
            y += ROW_HEIGHT + 2;
        }

        context.pose().popMatrix();
    }

    /**
     * Large, cinematic lyric overlay centered on the screen.
     */
    public void renderCinematic(GuiGraphicsExtractor context, Font font, SpotifyPlaybackState state,
                                int screenWidth, int screenHeight, float opacity) {
        LyricsService.Result result = lyricsService.resultFor(state);
        if (result.status() != LyricsService.Status.FOUND || result.lyrics().instrumental()) return;
        Lyrics lyrics = result.lyrics();
        int idx = currentIndex(lyrics, state);
        String current = idx < 0 ? null : display(lyrics.textAt(idx));
        String next = config.getLyricsUpcomingLines() > 0 ? lyrics.textAt(Math.max(0, idx + 1)) : null;
        if (current == null && next == null) return;

        float scale = Math.max(1.0f, config.getLyricsScale() * 1.45f);
        int sw = (int) (screenWidth / scale);
        int maxWidth = Math.min(520, sw - 32);
        List<Row> rows = new ArrayList<>();
        if (current != null && !current.equals("♪")) {
            for (String line : wrap(font, current, maxWidth, 2)) rows.add(new Row(line, COLOR_CURRENT, true));
        }
        if (next != null && !next.isBlank()) rows.add(new Row(truncate(font, next, maxWidth), 0xFFB7DCC5, false));
        if (rows.isEmpty()) return;

        int y = (int) ((screenHeight * 0.64f) / scale) - (rows.size() * 16) / 2;
        int bg = ((int) (Math.clamp(opacity, 0.2f, 1.0f) * 0.72f * 255.0f) << 24);
        context.pose().pushMatrix();
        context.pose().scale(scale, scale);
        for (Row row : rows) {
            int w = font.width(row.text());
            int x = sw / 2 - w / 2;
            context.fill(x - 14, y - 6, x + w + 14, y + 14, bg);
            if (row.current()) {
                context.fill(x - 8, y - 3, x - 5, y + 11, 0xFF1DB954);
            }
            context.text(font, row.text(), x, y, row.color());
            y += 19;
        }
        context.pose().popMatrix();
    }

    /**
     * Sky-style lyric overlay: compact floating lines near the upper third of the view.
     */
    public void renderSky(GuiGraphicsExtractor context, Font font, SpotifyPlaybackState state,
                          int screenWidth, int screenHeight, float opacity) {
        LyricsService.Result result = lyricsService.resultFor(state);
        if (result.status() != LyricsService.Status.FOUND || result.lyrics().instrumental()) return;
        Lyrics lyrics = result.lyrics();
        int idx = currentIndex(lyrics, state);
        String current = idx < 0 ? null : display(lyrics.textAt(idx));
        String next = config.getLyricsUpcomingLines() > 0 ? lyrics.textAt(Math.max(0, idx + 1)) : null;
        if (current == null && next == null) return;

        float scale = config.getLyricsScale();
        int sw = (int) (screenWidth / scale);
        int maxWidth = Math.min(440, sw - 30);
        List<Row> rows = new ArrayList<>();
        if (current != null && !current.equals("♪")) {
            for (String line : wrap(font, current, maxWidth, 2)) rows.add(new Row(line, 0xFFFFFFFF, true));
        }
        if (next != null && !next.isBlank()) rows.add(new Row(truncate(font, next, maxWidth), 0xFFB9D7CA, false));
        if (rows.isEmpty()) return;

        int y = (int) (screenHeight * 0.20f / scale);
        context.pose().pushMatrix();
        context.pose().scale(scale, scale);
        for (Row row : rows) {
            int w = font.width(row.text());
            int x = sw / 2 - w / 2;
            int alpha = (int) (Math.clamp(opacity, 0.2f, 1.0f) * (row.current() ? 0.68f : 0.42f) * 255);
            context.fill(x - 10, y - 4, x + w + 10, y + 13, (alpha << 24) | 0x0010151A);
            if (row.current()) context.fill(x - 7, y - 1, x - 4, y + 10, 0xFF1DB954);
            context.text(font, row.text(), x, y, row.color());
            y += 17;
        }
        context.pose().popMatrix();
    }

    // ----------------------------------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------------------------------

    private int currentIndex(Lyrics lyrics, SpotifyPlaybackState state) {
        long position = state.getExtrapolatedProgressMs() + config.getLyricsOffsetMs();
        return lyrics.indexAt(position, state.durationMs());
    }

    private static String display(String text) {
        return text == null || text.isBlank() ? "♪" : text;
    }

    static String truncate(Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;
        return font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width(ELLIPSIS))).stripTrailing() + ELLIPSIS;
    }

    /**
     * Greedy word wrap into at most {@code maxRows} rows; the last row is ellipsized if needed.
     * Long words (or scripts without spaces) are broken by width.
     */
    static List<String> wrap(Font font, String text, int maxWidth, int maxRows) {
        List<String> rows = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        String[] words = text.split(" ");
        int i = 0;
        while (i < words.length && rows.size() < maxRows) {
            String word = words[i];
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (font.width(candidate) <= maxWidth) {
                line.setLength(0);
                line.append(candidate);
                i++;
            } else if (line.isEmpty()) {
                // A single word wider than the row: hard-break it.
                String part = font.plainSubstrByWidth(word, maxWidth);
                if (part.isEmpty()) part = word.substring(0, 1);
                rows.add(part);
                words[i] = word.substring(part.length());
                if (words[i].isEmpty()) i++;
            } else {
                rows.add(line.toString());
                line.setLength(0);
            }
        }
        if (!line.isEmpty() && rows.size() < maxRows) {
            rows.add(line.toString());
        }
        if (rows.isEmpty()) rows.add("");

        // Anything left over gets an ellipsis on the last row.
        boolean leftover = i < words.length;
        if (leftover) {
            int last = rows.size() - 1;
            rows.set(last, truncate(font, rows.get(last) + " " + String.join(" ", java.util.Arrays.copyOfRange(words, i, words.length)), maxWidth));
        }
        return rows;
    }
}
