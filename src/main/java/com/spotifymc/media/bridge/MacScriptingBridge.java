package com.spotifymc.media.bridge;

import com.spotifymc.media.PlayerSnapshot;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * macOS implementation: asks the Spotify and Music apps directly via AppleScript
 * ({@code osascript}). Both apps ship scripting support, so no accounts are needed.
 *
 * <p>Apps are only scripted while they're already running (checked via the process list) so
 * we never launch them by accident. The first time, macOS asks the user to allow Minecraft to
 * control the app (Privacy &amp; Security &rarr; Automation).</p>
 */
public class MacScriptingBridge implements PlatformBridge {

    private static final long TIMEOUT_MS = 4_000;
    static final char SEP = (char) 30;

    /** Process executable suffix -> AppleScript application name. */
    private static final String[][] APPS = {
            {"/Spotify.app/Contents/MacOS/Spotify", "Spotify"},
            {"/Music.app/Contents/MacOS/Music", "Music"},
    };

    // Spotify: duration is in milliseconds and it exposes an artwork URL.
    private static final String SPOTIFY_SCRIPT = """
            on s(x)
            \ttry
            \t\tif x is missing value then return ""
            \t\treturn x as string
            \ton error
            \t\treturn ""
            \tend try
            end s
            set d to (character id 30)
            try
            \ttell application "Spotify"
            \t\tset st to (player state as string)
            \t\tif st is "stopped" then return st
            \t\tset t to current track
            \t\treturn st & d & my s(name of t) & d & my s(artist of t) & d & my s(album of t) & d & my s(duration of t) & d & my s(player position) & d & my s(artwork url of t) & d & my s(sound volume)
            \tend tell
            on error errMsg number errNum
            \treturn "error" & d & errNum & d & errMsg
            end try
            """;

    // Music: duration is in seconds and there is no artwork URL (looked up online instead).
    private static final String MUSIC_SCRIPT = """
            on s(x)
            \ttry
            \t\tif x is missing value then return ""
            \t\treturn x as string
            \ton error
            \t\treturn ""
            \tend try
            end s
            set d to (character id 30)
            try
            \ttell application "Music"
            \t\tset st to (player state as string)
            \t\tif st is "stopped" then return st
            \t\tset t to current track
            \t\treturn st & d & my s(name of t) & d & my s(artist of t) & d & my s(album of t) & d & my s(duration of t) & d & my s(player position) & d & "" & d & my s(sound volume)
            \tend tell
            on error errMsg number errNum
            \treturn "error" & d & errNum & d & errMsg
            end try
            """;

    @Override
    public List<PlayerSnapshot> poll() throws Exception {
        List<PlayerSnapshot> players = new ArrayList<>();
        String lastError = null;
        for (String app : runningApps()) {
            String script = app.equals("Spotify") ? SPOTIFY_SCRIPT : MUSIC_SCRIPT;
            ProcessRunner.Result r = ProcessRunner.run(List.of("osascript", "-e", script), TIMEOUT_MS);
            if (!r.ok()) {
                lastError = r.stderr().trim();
                continue;
            }
            try {
                PlayerSnapshot snap = parse(app, r.stdout());
                if (snap != null) players.add(snap);
            } catch (IOException e) {
                lastError = e.getMessage();
            }
        }
        if (players.isEmpty() && lastError != null && !lastError.isBlank()) {
            throw new IOException(lastError);
        }
        return players;
    }

    /**
     * Parses the AppleScript output. Package-private for tests.
     */
    static PlayerSnapshot parse(String app, String output) throws IOException {
        String out = output == null ? "" : output.strip();
        if (out.isEmpty() || out.equals("stopped")) return null;

        String[] f = out.split(String.valueOf(SEP), -1);
        if (f[0].equals("error")) {
            String code = f.length > 1 ? f[1] : "";
            if (code.equals("-1743")) {
                throw new IOException("Allow Minecraft to control " + displayName(app)
                        + " in System Settings > Privacy & Security > Automation");
            }
            throw new IOException(displayName(app) + ": " + (f.length > 2 ? f[2] : "scripting error"));
        }
        if (f.length < 8) return null;

        boolean playing = f[0].equalsIgnoreCase("playing");
        boolean paused = f[0].equalsIgnoreCase("paused");
        double duration = ProcessRunner.parseNumber(f[4]);
        long durationMs = app.equals("Spotify") ? Math.round(duration) : Math.round(duration * 1000.0);
        long positionMs = Math.round(ProcessRunner.parseNumber(f[5]) * 1000.0);
        String art = f[6].isBlank() ? null : f[6].trim();
        int volume = (int) Math.round(ProcessRunner.parseNumber(f[7]));

        return new PlayerSnapshot(app, playing, paused, f[1], f[2], f[3], durationMs, positionMs,
                durationMs > 0, art, f[7].isBlank() ? -1 : volume, playing);
    }

    @Override
    public void send(String appId, Command command) throws Exception {
        String verb = switch (command) {
            case TOGGLE -> "playpause";
            case NEXT -> "next track";
            case PREVIOUS -> "previous track";
        };
        tell(appId, verb);
    }

    @Override
    public boolean supportsVolume() {
        return true;
    }

    @Override
    public void setVolume(String appId, int percent) throws Exception {
        tell(appId, "set sound volume to " + Math.max(0, Math.min(100, percent)));
    }

    private void tell(String appId, String statement) throws Exception {
        String app = appId != null && appId.equals("Music") ? "Music" : "Spotify";
        ProcessRunner.Result r = ProcessRunner.run(
                List.of("osascript", "-e", "tell application \"" + app + "\" to " + statement), TIMEOUT_MS);
        if (!r.ok()) {
            throw new IOException(r.stderr().isBlank() ? "osascript failed" : r.stderr().trim());
        }
    }

    private static List<String> runningApps() {
        List<String> found = new ArrayList<>();
        ProcessHandle.allProcesses().forEach(ph -> {
            Optional<String> cmd = ph.info().command();
            if (cmd.isEmpty()) return;
            for (String[] app : APPS) {
                if (cmd.get().endsWith(app[0]) && !found.contains(app[1])) {
                    found.add(app[1]);
                }
            }
        });
        return found;
    }

    private static String displayName(String app) {
        return app.equals("Music") ? "Apple Music" : app;
    }
}
