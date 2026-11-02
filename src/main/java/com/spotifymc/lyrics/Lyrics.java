package com.spotifymc.lyrics;

import java.util.ArrayList;
import java.util.List;

/**
 * Lyrics for one track. Either time-synced (each line has a start time), plain text only,
 * or marked instrumental.
 */
public record Lyrics(List<Line> lines, boolean synced, boolean instrumental) {

    public record Line(long timeMs, String text) {
    }

    public static Lyrics synced(List<Line> lines) {
        return new Lyrics(List.copyOf(lines), true, false);
    }

    public static Lyrics plain(String text) {
        List<Line> lines = new ArrayList<>();
        for (String l : text.split("\\R")) {
            lines.add(new Line(-1, l.strip()));
        }
        // Drop leading/trailing blank lines.
        while (!lines.isEmpty() && lines.get(0).text().isEmpty()) lines.remove(0);
        while (!lines.isEmpty() && lines.get(lines.size() - 1).text().isEmpty()) lines.remove(lines.size() - 1);
        return new Lyrics(List.copyOf(lines), false, false);
    }

    public static Lyrics instrumentalTrack() {
        return new Lyrics(List.of(), false, true);
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }

    /**
     * Index of the line being sung at {@code positionMs}, or -1 before the first line.
     * For unsynced lyrics this is an even-spread estimate across the track's duration.
     */
    public int indexAt(long positionMs, long durationMs) {
        if (lines.isEmpty()) return -1;
        if (!synced) {
            if (durationMs <= 0) return 0;
            double ratio = Math.max(0.0, Math.min(1.0, (double) positionMs / durationMs));
            return Math.min(lines.size() - 1, (int) (ratio * lines.size()));
        }
        int lo = 0;
        int hi = lines.size() - 1;
        int found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lines.get(mid).timeMs() <= positionMs) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found;
    }

    /**
     * Text of line {@code i}, or null if out of range.
     */
    public String textAt(int i) {
        return i >= 0 && i < lines.size() ? lines.get(i).text() : null;
    }
}
