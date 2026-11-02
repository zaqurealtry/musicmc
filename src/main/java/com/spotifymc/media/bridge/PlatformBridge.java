package com.spotifymc.media.bridge;

import com.spotifymc.media.PlayerSnapshot;

import java.util.List;

/**
 * Reads "now playing" information from the operating system's media controls and sends
 * transport commands back. Implementations are blocking and are only ever called from a
 * single background thread.
 */
public interface PlatformBridge extends AutoCloseable {

    enum Command { TOGGLE, NEXT, PREVIOUS }

    /**
     * @return all players the OS currently knows about (may be empty).
     * @throws Exception if the bridge itself is broken (the message is shown to the user).
     */
    List<PlayerSnapshot> poll() throws Exception;

    /**
     * Sends a transport command to the given player.
     */
    void send(String appId, Command command) throws Exception;

    /**
     * Whether {@link #setVolume} works on this platform.
     */
    default boolean supportsVolume() {
        return false;
    }

    default void setVolume(String appId, int percent) throws Exception {
        throw new UnsupportedOperationException("Volume control is not supported here.");
    }

    @Override
    default void close() {
    }

    /**
     * Picks the right bridge for the current operating system.
     *
     * @param workDir a writable folder for helper files (the mod's config folder)
     */
    static PlatformBridge forCurrentOs(java.nio.file.Path workDir) {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("win")) return new WindowsSmtcBridge(workDir);
        if (os.contains("mac") || os.contains("darwin")) return new MacScriptingBridge();
        return new LinuxPlayerctlBridge();
    }
}
