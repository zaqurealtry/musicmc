package com.spotifymc.media.bridge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Runs a short-lived helper command (osascript, playerctl) and captures its output.
 */
final class ProcessRunner {

    record Result(int exitCode, String stdout, String stderr) {
        boolean ok() {
            return exitCode == 0;
        }
    }

    private ProcessRunner() {
    }

    static Result run(List<String> command, long timeoutMs) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(command).start();
        p.getOutputStream().close();
        CompletableFuture<String> out = readAsync(p.getInputStream());
        CompletableFuture<String> err = readAsync(p.getErrorStream());
        if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            p.destroyForcibly();
            throw new IOException("'" + command.get(0) + "' timed out");
        }
        return new Result(p.exitValue(), out.join(), err.join());
    }

    private static CompletableFuture<String> readAsync(InputStream in) {
        return CompletableFuture.supplyAsync(() -> {
            try (in) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        });
    }

    static double parseNumber(String s) {
        if (s == null) return 0;
        String t = s.trim().replace(',', '.');
        if (t.isEmpty()) return 0;
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
