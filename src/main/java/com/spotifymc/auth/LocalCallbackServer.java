package com.spotifymc.auth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Lightweight HTTP server running on 127.0.0.1 to intercept Spotify's OAuth 2.0 authorization redirect.
 */
public class LocalCallbackServer {

    private static final int PORT = 8888;
    private static final String PATH = "/callback";
    private static final int TIMEOUT_SECONDS = 180; // 3 minutes timeout

    private HttpServer server;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "MusicMC-OAuth-Timeout");
        t.setDaemon(true);
        return t;
    });

    /**
     * Starts the callback listener and returns a CompletableFuture that completes with the authorization code.
     *
     * @param expectedState The expected CSRF state parameter.
     * @return Future completing with authorization code, or exceptionally if canceled/timed out.
     */
    public CompletableFuture<String> startListening(String expectedState) {
        CompletableFuture<String> future = new CompletableFuture<>();

        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
            server.createContext(PATH, new CallbackHandler(expectedState, future));
            server.setExecutor(Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "MusicMC-OAuth-Handler");
                t.setDaemon(true);
                return t;
            }));
            server.start();

            // Schedule timeout cleanup
            scheduler.schedule(() -> {
                if (!future.isDone()) {
                    future.completeExceptionally(new RuntimeException("Authentication timed out after " + TIMEOUT_SECONDS + " seconds."));
                    stop();
                }
            }, TIMEOUT_SECONDS, TimeUnit.SECONDS);

        } catch (IOException e) {
            future.completeExceptionally(new RuntimeException("Failed to bind local callback server on port " + PORT + ". Is another instance running?", e));
        }

        return future;
    }

    /**
     * Stops the local HTTP server and releases port resources.
     */
    public synchronized void stop() {
        if (server != null) {
            try {
                server.stop(1);
            } catch (Exception ignored) {
            }
            server = null;
        }
        scheduler.shutdownNow();
    }

    public static int getPort() {
        return PORT;
    }

    public static String getRedirectUri() {
        return "http://127.0.0.1:" + PORT + PATH;
    }

    private class CallbackHandler implements HttpHandler {
        private final String expectedState;
        private final CompletableFuture<String> future;

        public CallbackHandler(String expectedState, CompletableFuture<String> future) {
            this.expectedState = expectedState;
            this.future = future;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            String query = exchange.getRequestURI().getRawQuery();
            Map<String, String> params = parseQuery(query);

            String error = params.get("error");
            String code = params.get("code");
            String state = params.get("state");

            String htmlResponse;
            int statusCode;

            if (error != null) {
                statusCode = 400;
                htmlResponse = renderHtml("Authentication Denied",
                        "Spotify returned error: <strong>" + escapeHtml(error) + "</strong>.<br>You can close this tab.",
                        false);
                future.completeExceptionally(new RuntimeException("OAuth authorization failed: " + error));
            } else if (code == null || state == null) {
                statusCode = 400;
                htmlResponse = renderHtml("Missing Parameters",
                        "Missing authorization code or state in callback URL.",
                        false);
                future.completeExceptionally(new RuntimeException("Missing code or state"));
            } else if (!expectedState.equals(state)) {
                statusCode = 400;
                htmlResponse = renderHtml("Security Validation Error",
                        "CSRF State parameter mismatch. Request discarded.",
                        false);
                future.completeExceptionally(new SecurityException("State mismatch"));
            } else {
                statusCode = 200;
                htmlResponse = renderHtml("Successfully Connected!",
                        "Minecraft has successfully linked with Spotify.<br>You can close this window and return to your game!",
                        true);
                future.complete(code);
            }

            byte[] bytes = htmlResponse.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(statusCode, bytes.length);

            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }

            // Cleanly stop server in background after short delay
            scheduler.schedule(LocalCallbackServer.this::stop, 1, TimeUnit.SECONDS);
        }

        private Map<String, String> parseQuery(String query) {
            Map<String, String> result = new HashMap<>();
            if (query == null || query.isBlank()) {
                return result;
            }
            String[] pairs = query.split("&");
            for (String pair : pairs) {
                int idx = pair.indexOf("=");
                if (idx > 0) {
                    String key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
                    String val = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
                    result.put(key, val);
                }
            }
            return result;
        }

        private String renderHtml(String title, String message, boolean success) {
            String accentColor = success ? "#1DB954" : "#E22134";
            String icon = success ? "✓" : "✗";

            return "<!DOCTYPE html>\n" +
                    "<html lang=\"en\">\n" +
                    "<head>\n" +
                    "  <meta charset=\"UTF-8\">\n" +
                    "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                    "  <title>" + escapeHtml(title) + " - Music MC</title>\n" +
                    "  <style>\n" +
                    "    body {\n" +
                    "      margin: 0;\n" +
                    "      padding: 0;\n" +
                    "      display: flex;\n" +
                    "      justify-content: center;\n" +
                    "      align-items: center;\n" +
                    "      min-height: 100vh;\n" +
                    "      background: #121212;\n" +
                    "      font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif;\n" +
                    "      color: #FFFFFF;\n" +
                    "    }\n" +
                    "    .card {\n" +
                    "      background: #181818;\n" +
                    "      border: 1px solid #282828;\n" +
                    "      border-radius: 16px;\n" +
                    "      padding: 40px;\n" +
                    "      text-align: center;\n" +
                    "      box-shadow: 0 16px 32px rgba(0, 0, 0, 0.4);\n" +
                    "      max-width: 420px;\n" +
                    "      width: 90%;\n" +
                    "    }\n" +
                    "    .icon {\n" +
                    "      display: inline-flex;\n" +
                    "      justify-content: center;\n" +
                    "      align-items: center;\n" +
                    "      width: 72px;\n" +
                    "      height: 72px;\n" +
                    "      border-radius: 50%;\n" +
                    "      background: " + accentColor + ";\n" +
                    "      color: #000000;\n" +
                    "      font-size: 38px;\n" +
                    "      font-weight: bold;\n" +
                    "      margin-bottom: 24px;\n" +
                    "    }\n" +
                    "    h1 {\n" +
                    "      margin: 0 0 12px 0;\n" +
                    "      font-size: 24px;\n" +
                    "      font-weight: 700;\n" +
                    "    }\n" +
                    "    p {\n" +
                    "      color: #B3B3B3;\n" +
                    "      font-size: 15px;\n" +
                    "      line-height: 1.5;\n" +
                    "      margin: 0;\n" +
                    "    }\n" +
                    "    .badge {\n" +
                    "      margin-top: 24px;\n" +
                    "      display: inline-block;\n" +
                    "      padding: 6px 14px;\n" +
                    "      border-radius: 20px;\n" +
                    "      background: #282828;\n" +
                    "      color: #A7A7A7;\n" +
                    "      font-size: 12px;\n" +
                    "      text-transform: uppercase;\n" +
                    "      letter-spacing: 1px;\n" +
                    "    }\n" +
                    "  </style>\n" +
                    "</head>\n" +
                    "<body>\n" +
                    "  <div class=\"card\">\n" +
                    "    <div class=\"icon\">" + icon + "</div>\n" +
                    "    <h1>" + escapeHtml(title) + "</h1>\n" +
                    "    <p>" + message + "</p>\n" +
                    "    <div class=\"badge\">Minecraft Spotify Integration</div>\n" +
                    "  </div>\n" +
                    "</body>\n" +
                    "</html>";
        }

        private String escapeHtml(String str) {
            if (str == null) return "";
            return str.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;")
                    .replace("'", "&#39;");
        }
    }
}
