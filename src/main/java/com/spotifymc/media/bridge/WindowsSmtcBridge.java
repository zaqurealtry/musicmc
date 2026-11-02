package com.spotifymc.media.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.spotifymc.media.PlayerSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Windows implementation: talks to the System Media Transport Controls (the media overlay
 * that appears when you press the volume keys) through a long-running, hidden Windows
 * PowerShell 5.1 process running {@code smtc-bridge.ps1}.
 *
 * <p>No native libraries are shipped and no accounts are needed: any app that shows up in the
 * Windows media overlay (Spotify, Apple Music, iTunes, browsers...) is visible here.</p>
 */
public class WindowsSmtcBridge implements PlatformBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("spotifymc");
    private static final String SCRIPT_RESOURCE = "/assets/spotifymc/scripts/smtc-bridge.ps1";
    private static final long STARTUP_TIMEOUT_MS = 15_000;
    private static final long REQUEST_TIMEOUT_MS = 6_000;

    private final Path workDir;

    private Process process;
    private BufferedWriter stdin;
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();

    private long nextStartAttemptMs = 0;
    private long restartBackoffMs = 5_000;
    private String lastError = "Starting Windows media bridge...";

    public WindowsSmtcBridge(Path workDir) {
        this.workDir = workDir;
    }

    @Override
    public List<PlayerSnapshot> poll() throws Exception {
        JsonObject response = request("state");
        if (!response.has("sessions") || !response.get("sessions").isJsonArray()) {
            throw new IOException(stringOr(response, "error", "Unexpected response from Windows media bridge"));
        }
        return parseSessions(response.getAsJsonArray("sessions"));
    }

    @Override
    public void send(String appId, Command command) throws Exception {
        String verb = switch (command) {
            case TOGGLE -> "toggle";
            case NEXT -> "next";
            case PREVIOUS -> "prev";
        };
        JsonObject response = request(verb + " " + (appId == null ? "" : appId));
        if (!response.has("ok") || !response.get("ok").getAsBoolean()) {
            throw new IOException(stringOr(response, "error", "The player ignored the command"));
        }
    }

    // ----------------------------------------------------------------------------------------
    // Parsing (package-private static so it can be unit tested without Windows)
    // ----------------------------------------------------------------------------------------

    static List<PlayerSnapshot> parseSessions(JsonArray sessions) {
        List<PlayerSnapshot> result = new ArrayList<>();
        for (JsonElement el : sessions) {
            if (!el.isJsonObject()) continue;
            JsonObject s = el.getAsJsonObject();
            String status = stringOr(s, "status", "");
            String art = stringOr(s, "art", "");
            String artUrl = art.isBlank() ? null : Path.of(art).toUri().toString();
            result.add(new PlayerSnapshot(
                    stringOr(s, "app", ""),
                    status.equalsIgnoreCase("Playing"),
                    status.equalsIgnoreCase("Paused"),
                    stringOr(s, "title", ""),
                    stringOr(s, "artist", ""),
                    stringOr(s, "album", ""),
                    longOr(s, "durationMs", 0),
                    longOr(s, "positionMs", 0),
                    s.has("positionKnown") && s.get("positionKnown").getAsBoolean(),
                    artUrl,
                    -1,
                    s.has("current") && s.get("current").getAsBoolean()
            ));
        }
        return result;
    }

    private static String stringOr(JsonObject o, String key, String fallback) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : fallback;
    }

    private static long longOr(JsonObject o, String key, long fallback) {
        try {
            return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsLong() : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    // ----------------------------------------------------------------------------------------
    // Process management
    // ----------------------------------------------------------------------------------------

    private synchronized JsonObject request(String command) throws IOException, InterruptedException {
        ensureStarted();
        lines.clear();
        try {
            stdin.write(command);
            stdin.write('\n');
            stdin.flush();
        } catch (IOException e) {
            fail("Windows media bridge stopped unexpectedly");
            throw new IOException(lastError, e);
        }

        String line = lines.poll(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        if (line == null) {
            fail("Windows media bridge timed out");
            throw new IOException(lastError);
        }
        restartBackoffMs = 5_000;
        return JsonParser.parseString(line).getAsJsonObject();
    }

    private void ensureStarted() throws IOException, InterruptedException {
        if (process != null && process.isAlive()) {
            return;
        }
        if (System.currentTimeMillis() < nextStartAttemptMs) {
            throw new IOException(lastError);
        }

        Path script = extractScript();
        List<String> cmd = Arrays.asList(
                findPowerShell(),
                "-NoLogo", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass",
                "-File", script.toString()
        );
        LOGGER.info("[Music MC] Starting Windows media bridge");

        lines.clear();
        try {
            process = new ProcessBuilder(cmd).start();
        } catch (IOException e) {
            fail("Windows PowerShell was not found, so system media can't be read");
            throw new IOException(lastError, e);
        }
        stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        pump(process.getInputStream(), true);
        pump(process.getErrorStream(), false);

        String first = lines.poll(STARTUP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        if (first == null) {
            fail("Windows media bridge did not start in time");
            throw new IOException(lastError);
        }
        JsonObject hello;
        try {
            hello = JsonParser.parseString(first).getAsJsonObject();
        } catch (Exception e) {
            fail("Windows media bridge sent an unexpected reply");
            throw new IOException(lastError, e);
        }
        if (!hello.has("ready") || !hello.get("ready").getAsBoolean()) {
            fail(stringOr(hello, "error", "Windows media controls are unavailable"));
            throw new IOException(lastError);
        }
    }

    private void pump(InputStream stream, boolean isStdout) {
        Thread t = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (isStdout) {
                        String trimmed = line.replace("\uFEFF", "").trim(); // tolerate a UTF-8 BOM
                        // Ignore anything that isn't one of our JSON replies (e.g. stray host output).
                        if (trimmed.startsWith("{")) lines.offer(trimmed);
                    } else if (!line.isBlank()) {
                        LOGGER.debug("[Music MC] media bridge: {}", line);
                    }
                }
            } catch (IOException ignored) {
            }
        }, isStdout ? "MusicMC-SMTC-Out" : "MusicMC-SMTC-Err");
        t.setDaemon(true);
        t.start();
    }

    private void fail(String message) {
        lastError = message;
        LOGGER.warn("[Music MC] {}", message);
        destroyProcess();
        nextStartAttemptMs = System.currentTimeMillis() + restartBackoffMs;
        restartBackoffMs = Math.min(restartBackoffMs * 2, 60_000);
    }

    private Path extractScript() throws IOException {
        Files.createDirectories(workDir);
        Path target = workDir.resolve("smtc-bridge.ps1");
        try (InputStream in = WindowsSmtcBridge.class.getResourceAsStream(SCRIPT_RESOURCE)) {
            if (in == null) throw new IOException("smtc-bridge.ps1 is missing from the mod jar");
            byte[] bytes = in.readAllBytes();
            if (!Files.exists(target) || !Arrays.equals(Files.readAllBytes(target), bytes)) {
                Files.write(target, bytes);
            }
        }
        return target;
    }

    private static String findPowerShell() {
        String root = System.getenv("SystemRoot");
        if (root != null) {
            Path p = Path.of(root, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
            if (Files.exists(p)) return p.toString();
        }
        return "powershell.exe";
    }

    private void destroyProcess() {
        Process p = process;
        process = null;
        if (p == null) return;
        try {
            if (stdin != null) {
                stdin.write("quit\n");
                stdin.flush();
            }
        } catch (IOException ignored) {
        }
        try {
            if (!p.waitFor(500, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
            }
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public synchronized void close() {
        destroyProcess();
    }
}
