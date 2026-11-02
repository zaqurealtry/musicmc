package com.spotifymc.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Utility class for generating OAuth 2.0 PKCE (Proof Key for Code Exchange)
 * verifiers and SHA-256 code challenges per RFC 7636.
 */
public final class PkceUtils {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String UNRESERVED_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~";

    private PkceUtils() {
    }

    /**
     * Generates a cryptographically secure random code verifier string.
     *
     * @param length The length of the verifier (between 43 and 128 characters).
     * @return The randomly generated code verifier.
     */
    public static String generateCodeVerifier(int length) {
        if (length < 43 || length > 128) {
            throw new IllegalArgumentException("Verifier length must be between 43 and 128 characters");
        }
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            int index = RANDOM.nextInt(UNRESERVED_CHARS.length());
            sb.append(UNRESERVED_CHARS.charAt(index));
        }
        return sb.toString();
    }

    /**
     * Generates a 64-character code verifier (recommended default length).
     */
    public static String generateCodeVerifier() {
        return generateCodeVerifier(64);
    }

    /**
     * Generates a code challenge from the given verifier using SHA-256 and Base64URL encoding (without padding).
     *
     * @param codeVerifier The PKCE code verifier.
     * @return Base64URL-encoded SHA-256 code challenge.
     */
    public static String generateCodeChallenge(String codeVerifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 message digest algorithm not available", e);
        }
    }

    /**
     * Generates a random state string to prevent Cross-Site Request Forgery (CSRF).
     */
    public static String generateState() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
