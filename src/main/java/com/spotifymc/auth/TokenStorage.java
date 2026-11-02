package com.spotifymc.auth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Manages secure local storage of OAuth tokens using AES-GCM encryption.
 * Protects tokens from being stored in plaintext in the config folder.
 */
public class TokenStorage {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String ENCRYPTION_ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int GCM_IV_LENGTH = 12;

    private final Path credentialsFilePath;
    private final SecretKey encryptionKey;

    public record StoredTokens(String accessToken, String refreshToken, long expiresAtMs) {
        public boolean isExpired() {
            // Buffer by 60 seconds to refresh proactively before actual expiration
            return System.currentTimeMillis() >= (expiresAtMs - 60_000L);
        }

        @Override
        public String toString() {
            return "StoredTokens[accessToken=***REDACTED***, refreshToken=***REDACTED***, expiresAtMs=" + expiresAtMs + "]";
        }
    }

    public TokenStorage() {
        this(resolveDefaultConfigDir());
    }

    public TokenStorage(Path configDir) {
        try {
            Files.createDirectories(configDir);
        } catch (Exception ignored) {
        }
        this.credentialsFilePath = configDir.resolve("credentials.json");
        this.encryptionKey = deriveMachineKey();
    }

    private static Path resolveDefaultConfigDir() {
        try {
            if (FabricLoader.getInstance() != null && FabricLoader.getInstance().getConfigDir() != null) {
                return FabricLoader.getInstance().getConfigDir().resolve("spotifymc");
            }
        } catch (Throwable ignored) {
        }
        return Path.of(System.getProperty("java.io.tmpdir"), "spotifymc-test");
    }

    /**
     * Derives a machine-local AES-256 key based on local environment attributes and salt.
     * Prevents credentials.json from being simply copied or viewed in plaintext.
     */
    private SecretKey deriveMachineKey() {
        try {
            String seed = System.getProperty("user.name", "default")
                    + "-" + System.getProperty("os.name", "unknown")
                    + "-SpotifyMC-SecretSalt-v1";
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = sha256.digest(seed.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(keyBytes, "AES");
        } catch (Exception e) {
            throw new RuntimeException("Failed to derive encryption key", e);
        }
    }

    /**
     * Saves tokens encrypted to local disk.
     */
    public synchronized void saveTokens(String accessToken, String refreshToken, long expiresInSeconds) {
        try {
            long expiresAt = System.currentTimeMillis() + (expiresInSeconds * 1000L);

            JsonObject payload = new JsonObject();
            payload.addProperty("accessToken", accessToken);
            payload.addProperty("refreshToken", refreshToken);
            payload.addProperty("expiresAtMs", expiresAt);

            String plaintext = payload.toString();
            String encrypted = encrypt(plaintext);

            JsonObject fileObj = new JsonObject();
            fileObj.addProperty("version", 1);
            fileObj.addProperty("encryptedData", encrypted);

            File file = credentialsFilePath.toFile();
            try (FileWriter writer = new FileWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(fileObj, writer);
            }
        } catch (Exception e) {
            System.err.println("[MusicMC] Failed to save encrypted tokens: " + e.getMessage());
        }
    }

    /**
     * Reads and decrypts stored tokens from local disk.
     *
     * @return StoredTokens if present and valid, or null.
     */
    public synchronized StoredTokens loadTokens() {
        File file = credentialsFilePath.toFile();
        if (!file.exists() || file.length() == 0) {
            return null;
        }

        try (FileReader reader = new FileReader(file, StandardCharsets.UTF_8)) {
            JsonObject fileObj = JsonParser.parseReader(reader).getAsJsonObject();
            if (!fileObj.has("encryptedData")) {
                return null;
            }

            String encrypted = fileObj.get("encryptedData").getAsString();
            String decrypted = decrypt(encrypted);

            JsonObject payload = JsonParser.parseString(decrypted).getAsJsonObject();
            String accessToken = payload.get("accessToken").getAsString();
            String refreshToken = payload.has("refreshToken") && !payload.get("refreshToken").isJsonNull()
                    ? payload.get("refreshToken").getAsString()
                    : null;
            long expiresAtMs = payload.get("expiresAtMs").getAsLong();

            return new StoredTokens(accessToken, refreshToken, expiresAtMs);
        } catch (Exception e) {
            System.err.println("[MusicMC] Failed to read or decrypt stored tokens: " + e.getMessage());
            return null;
        }
    }

    /**
     * Clears and deletes the stored credentials.
     */
    public synchronized void clear() {
        try {
            Files.deleteIfExists(credentialsFilePath);
        } catch (Exception ignored) {
        }
    }

    public boolean hasTokens() {
        return Files.exists(credentialsFilePath);
    }

    private String encrypt(String plaintext) throws Exception {
        byte[] iv = new byte[GCM_IV_LENGTH];
        new SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance(ENCRYPTION_ALGORITHM);
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, spec);

        byte[] cipherText = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

        byte[] combined = new byte[iv.length + cipherText.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);

        return Base64.getEncoder().encodeToString(combined);
    }

    private String decrypt(String encryptedBase64) throws Exception {
        byte[] combined = Base64.getDecoder().decode(encryptedBase64);
        if (combined.length < GCM_IV_LENGTH) {
            throw new IllegalArgumentException("Corrupted encrypted data payload");
        }

        byte[] iv = new byte[GCM_IV_LENGTH];
        System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);

        int cipherTextLength = combined.length - GCM_IV_LENGTH;
        byte[] cipherText = new byte[cipherTextLength];
        System.arraycopy(combined, GCM_IV_LENGTH, cipherText, 0, cipherTextLength);

        Cipher cipher = Cipher.getInstance(ENCRYPTION_ALGORITHM);
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey, spec);

        byte[] decryptedBytes = cipher.doFinal(cipherText);
        return new String(decryptedBytes, StandardCharsets.UTF_8);
    }
}
