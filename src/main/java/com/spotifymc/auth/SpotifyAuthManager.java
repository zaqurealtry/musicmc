package com.spotifymc.auth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.spotifymc.config.SpotifyConfig;
import com.mojang.blaze3d.Blaze3D;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Manages OAuth 2.0 PKCE authentication with Spotify.
 * Handles login initiation, token exchange, auto-refresh, and local storage.
 */
public class SpotifyAuthManager {

    private static final String AUTH_ENDPOINT = "https://accounts.spotify.com/authorize";
    private static final String TOKEN_ENDPOINT = "https://accounts.spotify.com/api/token";
    private static final String SCOPES = "user-read-playback-state user-modify-playback-state user-read-currently-playing";

    private final SpotifyConfig config;
    private final TokenStorage tokenStorage;
    private final HttpClient httpClient;
    private final CopyOnWriteArrayList<Consumer<Boolean>> authListeners = new CopyOnWriteArrayList<>();

    private TokenStorage.StoredTokens currentTokens;
    private LocalCallbackServer callbackServer;
    private boolean isAuthenticating = false;

    public SpotifyAuthManager(SpotifyConfig config) {
        this.config = config;
        this.tokenStorage = new TokenStorage();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        // Load existing credentials on startup
        this.currentTokens = tokenStorage.loadTokens();
    }

    public void addAuthListener(Consumer<Boolean> listener) {
        authListeners.add(listener);
    }

    private void notifyListeners(boolean isAuthenticated) {
        for (Consumer<Boolean> listener : authListeners) {
            try {
                listener.accept(isAuthenticated);
            } catch (Exception ignored) {
            }
        }
    }

    public boolean isAuthenticated() {
        return currentTokens != null && currentTokens.accessToken() != null;
    }

    public boolean isAuthenticating() {
        return isAuthenticating;
    }

    /**
     * Initiates the PKCE OAuth 2.0 authorization flow by opening the user's browser.
     */
    public CompletableFuture<Void> startPkceLoginFlow() {
        if (isAuthenticating) {
            return CompletableFuture.failedFuture(new IllegalStateException("Authentication is already in progress."));
        }

        String clientId = config.getClientId();
        if (clientId == null || clientId.isBlank() || clientId.equals("YOUR_SPOTIFY_CLIENT_ID")) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Please configure your Spotify Client ID first."));
        }

        isAuthenticating = true;
        String codeVerifier = PkceUtils.generateCodeVerifier();
        String codeChallenge = PkceUtils.generateCodeChallenge(codeVerifier);
        String state = PkceUtils.generateState();
        String redirectUri = LocalCallbackServer.getRedirectUri();

        // Stop any old server instance
        if (callbackServer != null) {
            callbackServer.stop();
        }
        callbackServer = new LocalCallbackServer();

        // Build authorization URL
        String authUrl = AUTH_ENDPOINT + "?" +
                "client_id=" + urlEncode(clientId) +
                "&response_type=code" +
                "&redirect_uri=" + urlEncode(redirectUri) +
                "&code_challenge_method=S256" +
                "&code_challenge=" + urlEncode(codeChallenge) +
                "&state=" + urlEncode(state) +
                "&scope=" + urlEncode(SCOPES);

        // Open user's default browser
        try {
            Blaze3D.openUri(URI.create(authUrl));
        } catch (Exception e) {
            isAuthenticating = false;
            return CompletableFuture.failedFuture(new RuntimeException("Failed to open web browser: " + e.getMessage(), e));
        }

        // Wait for callback
        return callbackServer.startListening(state)
                .thenCompose(code -> exchangeCodeForTokens(code, codeVerifier, redirectUri))
                .whenComplete((res, err) -> {
                    isAuthenticating = false;
                    if (callbackServer != null) {
                        callbackServer.stop();
                        callbackServer = null;
                    }
                    notifyListeners(isAuthenticated());
                });
    }

    /**
     * Exchanges the authorization code received from the redirect for access & refresh tokens.
     */
    private CompletableFuture<Void> exchangeCodeForTokens(String code, String codeVerifier, String redirectUri) {
        String body = "client_id=" + urlEncode(config.getClientId()) +
                "&grant_type=authorization_code" +
                "&code=" + urlEncode(code) +
                "&redirect_uri=" + urlEncode(redirectUri) +
                "&code_verifier=" + urlEncode(codeVerifier);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TOKEN_ENDPOINT))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(15))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> {
                    if (response.statusCode() != 200) {
                        throw new RuntimeException("Spotify Token Exchange failed (HTTP " + response.statusCode() + "): " + response.body());
                    }

                    JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                    String accessToken = json.get("access_token").getAsString();
                    String refreshToken = json.has("refresh_token") ? json.get("refresh_token").getAsString() : null;
                    long expiresIn = json.get("expires_in").getAsLong();

                    tokenStorage.saveTokens(accessToken, refreshToken, expiresIn);
                    this.currentTokens = tokenStorage.loadTokens();
                });
    }

    /**
     * Refreshes the Spotify access token using the stored refresh token.
     */
    public CompletableFuture<String> refreshAccessToken() {
        if (currentTokens == null || currentTokens.refreshToken() == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("No refresh token available. Re-authentication required."));
        }

        String body = "client_id=" + urlEncode(config.getClientId()) +
                "&grant_type=refresh_token" +
                "&refresh_token=" + urlEncode(currentTokens.refreshToken());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TOKEN_ENDPOINT))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(15))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        // If refresh token revoked, clear credentials
                        if (response.statusCode() == 400 || response.statusCode() == 401) {
                            logout();
                        }
                        throw new RuntimeException("Token refresh failed (HTTP " + response.statusCode() + ")");
                    }

                    JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                    String newAccessToken = json.get("access_token").getAsString();
                    String newRefreshToken = json.has("refresh_token") && !json.get("refresh_token").isJsonNull()
                            ? json.get("refresh_token").getAsString()
                            : currentTokens.refreshToken();
                    long expiresIn = json.get("expires_in").getAsLong();

                    tokenStorage.saveTokens(newAccessToken, newRefreshToken, expiresIn);
                    this.currentTokens = tokenStorage.loadTokens();
                    return newAccessToken;
                });
    }

    /**
     * Gets a guaranteed valid access token, proactively refreshing it if it is near expiry.
     */
    public CompletableFuture<String> getValidAccessToken() {
        if (currentTokens == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Not logged in to Spotify."));
        }

        if (currentTokens.isExpired()) {
            return refreshAccessToken();
        }

        return CompletableFuture.completedFuture(currentTokens.accessToken());
    }

    /**
     * Logs out the user and securely wipes saved credentials.
     */
    public void logout() {
        this.currentTokens = null;
        tokenStorage.clear();
        if (callbackServer != null) {
            callbackServer.stop();
            callbackServer = null;
        }
        isAuthenticating = false;
        notifyListeners(false);
    }

    private static String urlEncode(String value) {
        if (value == null) return "";
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
