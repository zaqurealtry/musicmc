package com.spotifymc.hud;

import com.mojang.blaze3d.platform.NativeImage;
import com.spotifymc.SpotifyMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Asynchronously downloads and caches album artwork.
 * Decodes images using STB and ImageIO fallback, and registers textures in Minecraft's TextureManager.
 */
public class AlbumArtCache {

    private static final int MAX_CACHE_SIZE = 30;
    private final HttpClient httpClient;

    // LRU texture cache
    private final Map<String, Identifier> textureCache = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Identifier> eldest) {
                    if (size() > MAX_CACHE_SIZE) {
                        destroyTexture(eldest.getValue());
                        return true;
                    }
                    return false;
                }
            }
    );

    // Track in-flight downloads and temporary failure timestamps (30s retry cool-down)
    private final Set<String> pendingDownloads = Collections.synchronizedSet(new HashSet<>());
    private final Map<String, Long> failedUrls = Collections.synchronizedMap(new LinkedHashMap<>());

    public AlbumArtCache() {
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Retrieves the Identifier for the cached album art texture, or initiates an asynchronous download.
     *
     * @param url The image URL.
     * @return The Identifier if already cached and uploaded to GPU, or null if loading.
     */
    public Identifier getOrFetchArtwork(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }

        Long failedAt = failedUrls.get(url);
        if (failedAt != null) {
            if (System.currentTimeMillis() - failedAt < 30_000L) {
                return null;
            } else {
                failedUrls.remove(url);
            }
        }

        Identifier cached = textureCache.get(url);
        if (cached != null) {
            return cached;
        }

        if (pendingDownloads.add(url)) {
            fetchArtworkAsync(url);
        }

        return null;
    }

    private void fetchArtworkAsync(String url) {
        if (url.startsWith("file:")) {
            // Local cover art written by the system media bridge (e.g. Windows media thumbnails).
            CompletableFuture.supplyAsync(() -> {
                try {
                    return Files.readAllBytes(Path.of(URI.create(url)));
                } catch (Exception e) {
                    throw new RuntimeException(e.getMessage(), e);
                }
            }).thenAccept(bytes -> onDownloaded(url, bytes))
                    .exceptionally(err -> onFailed(url, err.getMessage()));
            return;
        }

        SpotifyMod.LOGGER.debug("[Music MC] Downloading album art: {}", url);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
                .header("Accept", "image/jpeg,image/png,image/*;q=0.9,*/*;q=0.8")
                .timeout(Duration.ofSeconds(12))
                .GET()
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .thenAccept(response -> {
                    if (response.statusCode() == 200) {
                        onDownloaded(url, response.body());
                    } else {
                        onFailed(url, "HTTP " + response.statusCode());
                    }
                })
                .exceptionally(err -> onFailed(url, err.getMessage()));
    }

    private void onDownloaded(String url, byte[] imageBytes) {
        SpotifyMod.LOGGER.debug("[Music MC] Loaded {} bytes of artwork.", imageBytes.length);
        // Schedule texture registration on render thread
        Minecraft.getInstance().execute(() -> {
            try {
                registerTextureOnRenderThread(url, imageBytes);
            } finally {
                pendingDownloads.remove(url);
            }
        });
    }

    private Void onFailed(String url, String reason) {
        SpotifyMod.LOGGER.warn("[Music MC] Failed to load artwork from {}: {}", url, reason);
        pendingDownloads.remove(url);
        failedUrls.put(url, System.currentTimeMillis());
        return null;
    }

    private void registerTextureOnRenderThread(String url, byte[] imageBytes) {
        NativeImage nativeImage = null;

        // 1. Try STB native image decoder
        try {
            nativeImage = NativeImage.read(imageBytes);
        } catch (Throwable t1) {
            SpotifyMod.LOGGER.warn("[Music MC] NativeImage.read failed, falling back to Java ImageIO: {}", t1.getMessage());
        }

        // 2. Fallback to Java ImageIO for progressive or non-standard JPEGs
        if (nativeImage == null) {
            try (InputStream in = new ByteArrayInputStream(imageBytes)) {
                BufferedImage bImg = ImageIO.read(in);
                if (bImg != null) {
                    nativeImage = new NativeImage(bImg.getWidth(), bImg.getHeight(), false);
                    for (int y = 0; y < bImg.getHeight(); y++) {
                        for (int x = 0; x < bImg.getWidth(); x++) {
                            int rgb = bImg.getRGB(x, y);
                            // NativeImage.setPixel automatically converts ARGB to internal ABGR format
                            nativeImage.setPixel(x, y, rgb);
                        }
                    }
                }
            } catch (Throwable t2) {
                SpotifyMod.LOGGER.error("[Music MC] ImageIO also failed to decode image: {}", t2.getMessage());
            }
        }

        if (nativeImage == null) {
            SpotifyMod.LOGGER.error("[Music MC] Could not decode artwork image format.");
            failedUrls.put(url, System.currentTimeMillis());
            return;
        }

        DynamicTexture texture = null;
        try {
            texture = new DynamicTexture(() -> "spotify_art", nativeImage);
            String safeKey = "art_" + Integer.toHexString(url.hashCode()).toLowerCase();
            Identifier identifier = Identifier.fromNamespaceAndPath("spotifymc", safeKey);
            Minecraft.getInstance().getTextureManager().register(identifier, texture);

            textureCache.put(url, identifier);
            SpotifyMod.LOGGER.debug("[Music MC] Registered dynamic texture: {}", identifier);
        } catch (Exception e) {
            SpotifyMod.LOGGER.error("[Music MC] Failed to register dynamic texture on GPU: {}", e.getMessage(), e);
            failedUrls.put(url, System.currentTimeMillis());
            if (texture != null) {
                try {
                    texture.close();
                } catch (Exception ignored) {
                }
            } else {
                try {
                    nativeImage.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void destroyTexture(Identifier identifier) {
        if (identifier != null) {
            Minecraft.getInstance().execute(() -> {
                try {
                    Minecraft.getInstance().getTextureManager().release(identifier);
                } catch (Exception ignored) {
                }
            });
        }
    }

    public void clear() {
        synchronized (textureCache) {
            for (Identifier id : textureCache.values()) {
                destroyTexture(id);
            }
            textureCache.clear();
        }
        pendingDownloads.clear();
        failedUrls.clear();
    }
}
