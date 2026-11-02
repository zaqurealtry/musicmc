package com.spotifymc.media;

import com.spotifymc.api.SpotifyDevice;
import com.spotifymc.api.SpotifyPlaybackState;
import com.spotifymc.media.bridge.PlatformBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * The default, zero-setup source: reads whatever the operating system says is playing
 * (Windows media overlay, macOS app scripting, Linux MPRIS). Works for Spotify, Apple Music
 * and most other players without any login, developer account or API key.
 */
public class SystemMediaSource implements MediaSource {

    private static final Logger LOGGER = LoggerFactory.getLogger("spotifymc");

    private final Supplier<PreferredApp> preferredApp;
    private final Supplier<PlatformBridge> bridgeFactory;

    /** All bridge access happens on this one thread (the Windows bridge is a request/response pipe). */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MusicMC-SystemMedia");
        t.setDaemon(true);
        return t;
    });

    private PlatformBridge bridge;
    private volatile PlayerSnapshot selected;
    private volatile String lastError;
    private final LocalClock localClock = new LocalClock();

    public SystemMediaSource(Supplier<PreferredApp> preferredApp, Path workDir) {
        this(preferredApp, () -> PlatformBridge.forCurrentOs(workDir));
    }

    public SystemMediaSource(Supplier<PreferredApp> preferredApp, Supplier<PlatformBridge> bridgeFactory) {
        this.preferredApp = preferredApp;
        this.bridgeFactory = bridgeFactory;
    }

    private PlatformBridge bridge() {
        if (bridge == null) {
            bridge = bridgeFactory.get();
        }
        return bridge;
    }

    @Override
    public CompletableFuture<SpotifyPlaybackState> fetchState() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<PlayerSnapshot> players = bridge().poll();
                PreferredApp pref = Objects.requireNonNullElse(preferredApp.get(), PreferredApp.ANY);
                PlayerSnapshot previous = selected;
                PlayerSnapshot chosen = choose(players, pref, previous != null ? previous.appId() : null);
                selected = chosen == null ? null : normalize(chosen);
                if (lastError != null) {
                    LOGGER.info("[Music MC] System media is working again");
                    lastError = null;
                }
                return toState(selected, pref);
            } catch (Throwable t) {
                String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
                if (!msg.equals(lastError)) {
                    LOGGER.warn("[Music MC] Could not read system media: {}", msg);
                    lastError = msg;
                }
                return SpotifyPlaybackState.unavailable(msg);
            }
        }, executor);
    }

    // ----------------------------------------------------------------------------------------
    // Selection & normalisation (static so they can be unit tested)
    // ----------------------------------------------------------------------------------------

    /**
     * Picks which player to show. Preference order: playing over paused, then the player we were
     * already showing (so two open players don't flicker), then known music apps over browsers,
     * then whichever the OS marks as current.
     */
    static PlayerSnapshot choose(List<PlayerSnapshot> players, PreferredApp pref, String lastAppId) {
        PlayerSnapshot best = null;
        int bestScore = Integer.MIN_VALUE;
        for (PlayerSnapshot p : players) {
            if (!p.hasTrack() || !pref.matches(p.kind())) continue;
            int score = (p.playing() ? 8 : 0)
                    + (p.appId().equals(lastAppId) ? 4 : 0)
                    + (p.kind() != AppKind.OTHER ? 2 : 0)
                    + (p.systemCurrent() ? 1 : 0);
            if (score > bestScore) {
                best = p;
                bestScore = score;
            }
        }
        return best;
    }

    /**
     * Cleans up quirks of specific players. The Apple Music app on Windows reports
     * "Artist — Album" in the artist field and leaves the album empty.
     */
    static PlayerSnapshot normalize(PlayerSnapshot p) {
        String artist = p.artist() == null ? "" : p.artist().trim();
        String album = p.album() == null ? "" : p.album().trim();
        if (p.kind() == AppKind.APPLE_MUSIC && album.isEmpty()) {
            int idx = artist.indexOf(" — ");
            if (idx > 0) {
                album = artist.substring(idx + 3).trim();
                artist = artist.substring(0, idx).trim();
            }
        }
        if (artist.equals(p.artist()) && album.equals(p.album())) return p;
        return new PlayerSnapshot(p.appId(), p.playing(), p.paused(), p.title(), artist, album,
                p.durationMs(), p.positionMs(), p.positionKnown(), p.artUrl(), p.volumePercent(), p.systemCurrent());
    }

    private SpotifyPlaybackState toState(PlayerSnapshot p, PreferredApp pref) {
        if (p == null) {
            if (pref == PreferredApp.ANY) return SpotifyPlaybackState.NOTHING_PLAYING;
            return new SpotifyPlaybackState(SpotifyPlaybackState.Status.NO_ACTIVE_DEVICE, "Nothing Playing",
                    "Start playing something in " + pref.label(), "", null, 0, 0,
                    System.currentTimeMillis(), null, pref.label());
        }
        long now = System.currentTimeMillis();
        String key = p.appId() + "|" + p.title() + "|" + p.artist();
        long progress = p.positionKnown() ? p.positionMs() : localClock.update(key, p.playing(), now);
        String sourceName = AppKind.friendlyName(p.appId());
        SpotifyDevice device = new SpotifyDevice(p.appId(), sourceName, "Computer", true,
                p.volumePercent() >= 0 ? p.volumePercent() : 50);
        return new SpotifyPlaybackState(
                p.playing() ? SpotifyPlaybackState.Status.PLAYING : SpotifyPlaybackState.Status.PAUSED,
                p.title(),
                p.artist().isBlank() ? sourceName : p.artist(),
                p.album(),
                p.artUrl(),
                progress,
                p.durationMs(),
                now,
                device,
                sourceName
        );
    }

    // ----------------------------------------------------------------------------------------
    // Controls
    // ----------------------------------------------------------------------------------------

    @Override
    public CompletableFuture<Void> togglePlayPause(boolean currentlyPlaying) {
        return command(PlatformBridge.Command.TOGGLE);
    }

    @Override
    public CompletableFuture<Void> next() {
        return command(PlatformBridge.Command.NEXT);
    }

    @Override
    public CompletableFuture<Void> previous() {
        return command(PlatformBridge.Command.PREVIOUS);
    }

    private CompletableFuture<Void> command(PlatformBridge.Command command) {
        PlayerSnapshot target = selected;
        if (target == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("NO_ACTIVE_DEVICE"));
        }
        return CompletableFuture.runAsync(() -> {
            try {
                bridge().send(target.appId(), command);
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }, executor);
    }

    @Override
    public boolean supportsVolume() {
        // Windows' media controls have no volume; the macOS and Linux bridges do.
        return !System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    @Override
    public CompletableFuture<Void> setVolume(int percent) {
        PlayerSnapshot target = selected;
        if (target == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("NO_ACTIVE_DEVICE"));
        }
        return CompletableFuture.runAsync(() -> {
            try {
                bridge().setVolume(target.appId(), percent);
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }, executor);
    }

    @Override
    public long nextPollDelayMs(SpotifyPlaybackState state, int configuredIntervalSec) {
        // Local polling is cheap, and lyrics need to notice track changes quickly.
        return switch (state.status()) {
            case PLAYING -> 1_000L;
            case PAUSED -> 2_000L;
            case UNAVAILABLE -> 5_000L;
            default -> 3_000L;
        };
    }

    @Override
    public SpotifyPlaybackState initialState() {
        return SpotifyPlaybackState.NOTHING_PLAYING;
    }

    @Override
    public void deactivate() {
        executor.execute(() -> {
            if (bridge != null) {
                bridge.close();
                bridge = null;
            }
            selected = null;
        });
    }

    @Override
    public void shutdown() {
        deactivate();
        executor.shutdown();
    }

    /**
     * Tracks playback position ourselves for players that don't publish a timeline:
     * starts at 0 when the track changes and only advances while playing.
     */
    static final class LocalClock {
        private String key;
        private long accumulatedMs;
        private long resumedAtMs = -1;

        long update(String trackKey, boolean playing, long now) {
            if (!trackKey.equals(key)) {
                key = trackKey;
                accumulatedMs = 0;
                resumedAtMs = playing ? now : -1;
                return 0;
            }
            if (playing && resumedAtMs < 0) {
                resumedAtMs = now;
            } else if (!playing && resumedAtMs >= 0) {
                accumulatedMs += now - resumedAtMs;
                resumedAtMs = -1;
            }
            return accumulatedMs + (resumedAtMs >= 0 ? now - resumedAtMs : 0);
        }
    }
}
