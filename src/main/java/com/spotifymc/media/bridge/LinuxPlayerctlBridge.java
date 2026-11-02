package com.spotifymc.media.bridge;

import com.spotifymc.media.PlayerSnapshot;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Linux implementation: reads MPRIS players through the {@code playerctl} command-line tool,
 * which is packaged by every major distro. Works with the Spotify desktop app, Cider (Apple
 * Music), browsers and most Linux music players.
 */
public class LinuxPlayerctlBridge implements PlatformBridge {

    private static final long TIMEOUT_MS = 3_000;
    static final char SEP = (char) 30;

    private static final String FORMAT = String.join(String.valueOf(SEP),
            "{{playerInstance}}", "{{status}}", "{{xesam:title}}", "{{xesam:artist}}", "{{xesam:album}}",
            "{{mpris:length}}", "{{position}}", "{{mpris:artUrl}}", "{{volume}}");

    @Override
    public List<PlayerSnapshot> poll() throws Exception {
        ProcessRunner.Result r;
        try {
            r = ProcessRunner.run(List.of("playerctl", "-a", "metadata", "--format", FORMAT), TIMEOUT_MS);
        } catch (IOException e) {
            throw new IOException("Install 'playerctl' to show what's playing (e.g. sudo apt install playerctl)", e);
        }
        if (!r.ok()) {
            // "No players found" is a normal, empty result.
            return List.of();
        }
        return parse(r.stdout());
    }

    /**
     * Parses playerctl output (one player per line). Package-private for tests.
     */
    static List<PlayerSnapshot> parse(String output) {
        List<PlayerSnapshot> players = new ArrayList<>();
        boolean first = true;
        for (String line : output.split("\\R")) {
            if (line.isBlank()) continue;
            String[] f = line.split(String.valueOf(SEP), -1);
            if (f.length < 9) continue;
            boolean playing = f[1].equalsIgnoreCase("Playing");
            boolean paused = f[1].equalsIgnoreCase("Paused");
            long durationMs = Math.round(ProcessRunner.parseNumber(f[5]) / 1000.0);  // microseconds
            long positionMs = Math.round(ProcessRunner.parseNumber(f[6]) / 1000.0);
            String art = f[7].isBlank() ? null : f[7].trim();
            int volume = f[8].isBlank() ? -1 : (int) Math.round(ProcessRunner.parseNumber(f[8]) * 100.0);
            players.add(new PlayerSnapshot(f[0], playing, paused, f[2], f[3], f[4], durationMs, positionMs,
                    durationMs > 0, art, volume, first));
            first = false;
        }
        return players;
    }

    @Override
    public void send(String appId, Command command) throws Exception {
        String verb = switch (command) {
            case TOGGLE -> "play-pause";
            case NEXT -> "next";
            case PREVIOUS -> "previous";
        };
        run(appId, verb);
    }

    @Override
    public boolean supportsVolume() {
        return true;
    }

    @Override
    public void setVolume(String appId, int percent) throws Exception {
        double v = Math.max(0, Math.min(100, percent)) / 100.0;
        run(appId, "volume", String.format(java.util.Locale.ROOT, "%.2f", v));
    }

    private void run(String appId, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("playerctl"));
        if (appId != null && !appId.isBlank()) {
            cmd.add("-p");
            cmd.add(appId);
        }
        cmd.addAll(List.of(args));
        ProcessRunner.Result r = ProcessRunner.run(cmd, TIMEOUT_MS);
        if (!r.ok()) {
            throw new IOException(r.stderr().isBlank() ? "playerctl failed" : r.stderr().trim());
        }
    }
}
