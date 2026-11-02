package com.spotifymc.lyrics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses LRC-format synced lyrics, e.g.
 * <pre>
 * [ar:Artist]
 * [offset:+250]
 * [00:12.34]First line
 * [00:15.00][01:15.00]Repeated chorus line
 * </pre>
 * Enhanced-LRC word timings ({@code <00:12.50>}) are stripped.
 */
public final class LrcParser {

    private static final Pattern TIME_TAG = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");
    private static final Pattern OFFSET_TAG = Pattern.compile("\\[offset:\\s*([+-]?\\d+)\\s*]", Pattern.CASE_INSENSITIVE);
    private static final Pattern WORD_TAG = Pattern.compile("<\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?>");

    private LrcParser() {
    }

    public static List<Lyrics.Line> parse(String lrc) {
        List<Lyrics.Line> lines = new ArrayList<>();
        if (lrc == null || lrc.isBlank()) return lines;

        long offset = 0;
        Matcher om = OFFSET_TAG.matcher(lrc);
        if (om.find()) {
            try {
                offset = Long.parseLong(om.group(1));
            } catch (NumberFormatException ignored) {
            }
        }

        for (String raw : lrc.split("\\R")) {
            String line = raw.strip();
            Matcher m = TIME_TAG.matcher(line);
            List<Long> times = new ArrayList<>();
            int textStart = 0;
            // Timestamps must be at the start of the line (possibly several in a row).
            while (m.find() && m.start() == textStart) {
                long minutes = Long.parseLong(m.group(1));
                long seconds = Long.parseLong(m.group(2));
                long millis = 0;
                if (m.group(3) != null) {
                    String frac = m.group(3);
                    millis = switch (frac.length()) {
                        case 1 -> Long.parseLong(frac) * 100;
                        case 2 -> Long.parseLong(frac) * 10;
                        default -> Long.parseLong(frac);
                    };
                }
                // Per the LRC spec a positive offset makes lyrics appear sooner.
                times.add(Math.max(0, minutes * 60_000 + seconds * 1_000 + millis - offset));
                textStart = m.end();
            }
            if (times.isEmpty()) continue; // metadata tag or junk

            String text = WORD_TAG.matcher(line.substring(textStart)).replaceAll("").strip();
            for (long t : times) {
                lines.add(new Lyrics.Line(t, text));
            }
        }

        lines.sort(Comparator.comparingLong(Lyrics.Line::timeMs));
        return lines;
    }
}
