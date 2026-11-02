package com.spotifymc.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class TokenStorageTest {

    private final TokenStorage storage = new TokenStorage();

    @AfterEach
    public void tearDown() {
        storage.clear();
    }

    @Test
    public void testSaveAndLoadTokens() {
        String testAccessToken = "test_access_token_1234567890";
        String testRefreshToken = "test_refresh_token_abcdefghij";
        long expiresIn = 3600; // 1 hour

        storage.saveTokens(testAccessToken, testRefreshToken, expiresIn);

        assertTrue(storage.hasTokens());
        TokenStorage.StoredTokens loaded = storage.loadTokens();

        assertNotNull(loaded);
        assertEquals(testAccessToken, loaded.accessToken());
        assertEquals(testRefreshToken, loaded.refreshToken());
        assertFalse(loaded.isExpired());
    }

    @Test
    public void testClearTokens() {
        storage.saveTokens("tokenA", "tokenB", 1800);
        assertTrue(storage.hasTokens());

        storage.clear();
        assertFalse(storage.hasTokens());
        assertNull(storage.loadTokens());
    }

    @Test
    public void testTokenToStringDoesNotLeakSecrets() {
        TokenStorage.StoredTokens tokens = new TokenStorage.StoredTokens("secret_access", "secret_refresh", 100000);
        String stringRep = tokens.toString();

        assertFalse(stringRep.contains("secret_access"), "toString must not expose access token");
        assertFalse(stringRep.contains("secret_refresh"), "toString must not expose refresh token");
        assertTrue(stringRep.contains("REDACTED"));
    }
}
