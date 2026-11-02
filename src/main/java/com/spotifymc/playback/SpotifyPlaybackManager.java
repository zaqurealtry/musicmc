package com.spotifymc.playback;

import com.spotifymc.api.SpotifyDevice;
import com.spotifymc.api.SpotifyPlaybackState;
import com.spotifymc.auth.SpotifyAuthManager;
import com.spotifymc.config.SpotifyConfig;
import com.spotifymc.lyrics.LyricsService;
import com.spotifymc.media.MediaSource;
import com.spotifymc.media.SpotifyApiSource;
import com.spotifymc.media.SystemMediaSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Coordinates playback state polling, state caching, and player controls for whichever
 * {@link MediaSource} is active (system media by default, or the Spotify Web API).
 */
public class SpotifyPlaybackManager {

    private final SpotifyConfig config;
    private final SpotifyAuthManager authManager;
    private final SpotifyApiSource apiSource;
    private final SystemMediaSource systemSource;
    private final LyricsService lyricsService;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "MusicMC-Playback-Poller");
        t.setDaemon(true);
        return t;
    });

    private volatile MediaSource activeSource;
    private volatile SpotifyPlaybackState currentState;
    private ScheduledFuture<?> currentPollTask;
    private boolean isPolling = false;

    public SpotifyPlaybackManager(SpotifyConfig config, SpotifyAuthManager authManager, SpotifyApiSource apiSource,
                                  SystemMediaSource systemSource, LyricsService lyricsService) {
        this.config = config;
        this.authManager = authManager;
        this.apiSource = apiSource;
        this.systemSource = systemSource;
        this.lyricsService = lyricsService;

        this.activeSource = sourceFor(config.getSourceMode());
        this.currentState = activeSource.initialState();

        // Listen for Spotify Web API authentication changes
        this.authManager.addAuthListener(authenticated -> {
            if (activeSource != apiSource) return;
            if (authenticated) {
                sendInGameMessage("§a[Music MC] Connected to Spotify!");
                pollOnce();
            } else {
                currentState = SpotifyPlaybackState.NOT_AUTHENTICATED;
            }
        });

        startPolling();
    }

    public SpotifyPlaybackState getCurrentState() {
        return currentState;
    }

    public LyricsService getLyricsService() {
        return lyricsService;
    }

    public boolean isUsingSpotifyApi() {
        return activeSource == apiSource;
    }

    private MediaSource sourceFor(SpotifyConfig.SourceMode mode) {
        return mode == SpotifyConfig.SourceMode.SPOTIFY_API ? apiSource : systemSource;
    }

    /**
     * Applies a changed {@link SpotifyConfig#getSourceMode()} or preferred app immediately.
     */
    public synchronized void onSourceSettingsChanged() {
        MediaSource next = sourceFor(config.getSourceMode());
        if (next != activeSource) {
            activeSource.deactivate();
            activeSource = next;
            currentState = next.initialState();
        }
        scheduleNextPoll(100);
    }

    /**
     * Starts or restarts the adaptive polling schedule.
     */
    public synchronized void startPolling() {
        if (isPolling && currentPollTask != null && !currentPollTask.isCancelled()) {
            return;
        }
        isPolling = true;
        scheduleNextPoll(500);
    }

    private synchronized void scheduleNextPoll(long delayMs) {
        if (!isPolling || scheduler.isShutdown()) return;
        if (currentPollTask != null && !currentPollTask.isDone()) {
            currentPollTask.cancel(false);
        }
        currentPollTask = scheduler.schedule(this::pollAndReschedule, delayMs, TimeUnit.MILLISECONDS);
    }

    private void pollAndReschedule() {
        MediaSource source = activeSource;
        try {
            source.fetchState().whenComplete((state, err) -> {
                if (source != activeSource) {
                    return; // source was switched while we were polling; the switch scheduled a new poll
                }
                if (err != null || state == null) {
                    currentState = SpotifyPlaybackState.UNAVAILABLE;
                    scheduleNextPoll(8_000);
                    return;
                }
                applyState(state);
                scheduleNextPoll(source.nextPollDelayMs(state, config.getPollingIntervalSec()));
            });
        } catch (Exception e) {
            scheduleNextPoll(6_000);
        }
    }

    private void applyState(SpotifyPlaybackState state) {
        this.currentState = state;
        try {
            lyricsService.onStateUpdate(state);
        } catch (Exception ignored) {
        }
    }

    /**
     * Triggers an immediate state poll (e.g. after a play/pause action).
     */
    public void pollOnce() {
        scheduler.schedule(() -> {
            MediaSource source = activeSource;
            source.fetchState().thenAccept(state -> {
                if (source == activeSource && state != null) applyState(state);
            });
        }, 300, TimeUnit.MILLISECONDS);
    }

    // Controls
    public void togglePlayPause() {
        if (!checkReady()) return;
        MediaSource source = activeSource;
        boolean wasPlaying = currentState.status() == SpotifyPlaybackState.Status.PLAYING;
        source.togglePlayPause(wasPlaying).thenRun(() -> {
            currentState = currentState.withPlayback(
                    wasPlaying ? SpotifyPlaybackState.Status.PAUSED : SpotifyPlaybackState.Status.PLAYING,
                    currentState.getExtrapolatedProgressMs());
            pollOnce();
        }).exceptionally(this::handleControlError);
    }

    public void nextTrack() {
        if (!checkReady()) return;
        activeSource.next().thenRun(this::pollOnce).exceptionally(this::handleControlError);
    }

    public void previousTrack() {
        if (!checkReady()) return;
        activeSource.previous().thenRun(this::pollOnce).exceptionally(this::handleControlError);
    }

    // Debounced volume rate limiting
    private final Object volumeLock = new Object();
    private volatile int pendingTargetVolume = -1;
    private ScheduledFuture<?> volumeDebounceTask;

    public void adjustVolume(int delta) {
        if (!checkReady()) return;
        MediaSource source = activeSource;
        if (!source.supportsVolume()) {
            sendFeedback("§7♫ Volume control isn't available for this player on Windows — use the app or system volume.", true);
            return;
        }

        synchronized (volumeLock) {
            int baseVol = pendingTargetVolume >= 0 ? pendingTargetVolume
                    : (currentState.device() != null ? currentState.device().volumePercent() : 50);
            int newVol = Math.clamp(baseVol + delta, 0, 100);
            this.pendingTargetVolume = newVol;

            // Update local cached device volume immediately for responsive UI
            if (currentState.device() != null) {
                SpotifyDevice dev = currentState.device();
                this.currentState = currentState.withDevice(
                        new SpotifyDevice(dev.id(), dev.name(), dev.type(), dev.isActive(), newVol));
            }

            // Show clean action bar volume feedback above hotbar
            sendFeedback("§a♫ Volume: " + newVol + "%", true);

            // Debounce outgoing request (350ms window coalesces rapid key taps into a single request)
            if (volumeDebounceTask != null && !volumeDebounceTask.isDone()) {
                volumeDebounceTask.cancel(false);
            }

            volumeDebounceTask = scheduler.schedule(() -> {
                int volumeToSend;
                synchronized (volumeLock) {
                    volumeToSend = pendingTargetVolume;
                    pendingTargetVolume = -1;
                }
                if (volumeToSend >= 0) {
                    source.setVolume(volumeToSend).exceptionally(this::handleControlError);
                }
            }, 350, TimeUnit.MILLISECONDS);
        }
    }

    private Void handleControlError(Throwable err) {
        Throwable cause = err.getCause() != null ? err.getCause() : err;
        String msg = cause.getMessage();
        if ("PREMIUM_REQUIRED".equals(msg)) {
            sendInGameMessage("§c[Music MC] Spotify Premium is required for playback controls.");
        } else if ("NO_ACTIVE_DEVICE".equals(msg)) {
            sendInGameMessage(isUsingSpotifyApi()
                    ? "§6[Music MC] No active player device. Open Spotify on desktop/mobile first."
                    : "§6[Music MC] Nothing is playing. Start a song in Spotify or Apple Music first.");
        } else {
            sendInGameMessage("§c[Music MC] Action failed: " + (msg != null ? msg : "Unknown error"));
        }
        return null;
    }

    private boolean checkReady() {
        if (isUsingSpotifyApi() && !authManager.isAuthenticated()) {
            sendInGameMessage("§e[Music MC] You must connect your Spotify account first. Open Mod Settings.");
            return false;
        }
        if (!isUsingSpotifyApi() && currentState.status() == SpotifyPlaybackState.Status.UNAVAILABLE) {
            sendInGameMessage("§c[Music MC] Can't reach your music player: " + currentState.artistName());
            return false;
        }
        return true;
    }

    public static void sendInGameMessage(String message) {
        sendFeedback(message, false);
    }

    public static void sendFeedback(String message, boolean actionBar) {
        Minecraft client = Minecraft.getInstance();
        if (client != null) {
            client.execute(() -> {
                Component comp = Component.literal(message);
                if (actionBar && client.player != null) {
                    client.player.sendOverlayMessage(comp);
                } else if (client.player != null) {
                    client.player.sendSystemMessage(comp);
                } else if (client.gui != null && client.gui.chatListener() != null) {
                    client.gui.chatListener().handleSystemMessage(comp, false);
                }
            });
        }
    }

    public synchronized void shutdown() {
        isPolling = false;
        if (currentPollTask != null) {
            currentPollTask.cancel(true);
        }
        scheduler.shutdownNow();
        systemSource.shutdown();
        apiSource.shutdown();
        lyricsService.shutdown();
    }
}
