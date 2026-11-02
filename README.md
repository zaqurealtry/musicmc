# Music MC — Now Playing & Lyrics HUD for Minecraft (Spotify + Apple Music)

> Formerly **Spotify MC**. Existing settings and Spotify logins carry over automatically, and the old `/spotify` command still works.

[![Minecraft](https://img.shields.io/badge/Minecraft-26.3-brightgreen.svg?style=flat-square&logo=minecraft)](https://minecraft.net/)
[![Fabric](https://img.shields.io/badge/Modloader-Fabric-blue.svg?style=flat-square)](https://fabricmc.net/)
[![Java](https://img.shields.io/badge/Java-25+-orange.svg?style=flat-square&logo=openjdk)](https://adoptium.net/)
[![ModMenu](https://img.shields.io/badge/ModMenu-Supported-blueviolet.svg?style=flat-square)](https://modrinth.com/mod/modmenu)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg?style=flat-square)](LICENSE)

A lightweight, client-side Minecraft mod that shows what you're listening to (Spotify, Apple Music, and more), **synced lyrics**, album art and playback controls right in your game HUD.

**No login, no developer account, no API keys.** Install it, press play in your music app, done.

Built specifically for **Minecraft 26.3** using **Fabric Loader** and **Java 25**.

---

## Table of Contents

- [Overview](#overview)
- [Features](#features)
- [HUD Preview](#hud-preview)
- [Requirements](#requirements)
- [Player Installation](#player-installation)
- [How It Works (No Login)](#how-it-works-no-login)
- [Lyrics](#lyrics)
- [Optional: Spotify Web API Mode](#optional-spotify-web-api-mode)
- [Controls & Commands](#controls--commands)
- [Configuration](#configuration)
- [Technical Architecture & Security](#technical-architecture--security)
- [Building from Source](#building-from-source)
- [Troubleshooting & FAQ](#troubleshooting--faq)
- [License](#license)

---

## Overview

**Music MC** reads "now playing" information straight from your computer's own media controls, the same info your OS shows in its media overlay or lock screen. That means it works with **Spotify** and **Apple Music** (and most other players) without signing in to anything.

Lyrics come from [LRCLIB](https://lrclib.net), a free and open synced-lyrics database, so they work the same for every app.

If you'd rather use the Spotify Web API (for example to see and control playback on your phone or a speaker), that original mode is still available as an optional advanced setting.

### Highlights
- **Zero setup**: no Spotify developer app, no Apple developer account, no tokens.
- **Spotify + Apple Music**: auto-detects which one is playing, or pin the one you want.
- **Synced lyrics**: karaoke-style lines that follow the song, as a panel under the HUD or as subtitles.
- **Client-Side Only**: does not need to be installed on servers.
- **No native libraries**: uses tools already on your OS (Windows PowerShell, macOS AppleScript, Linux `playerctl`).
- **Privacy**: only the song title/artist are sent to the lyrics/artwork services. Optional Spotify API tokens stay AES-GCM encrypted on disk.

---

## Features

- **Dynamic HUD Overlay**:
  - Song title, artist and album with automatic **marquee scrolling** for long names.
  - Shows which app is playing (`▶ Playing · Apple Music`) and matches its color (Spotify green / Apple Music red), or use your own accent.
  - Album artwork (from the player, or looked up automatically when the player doesn't provide it).
  - Smooth, extrapolated **progress bar**.
- **Synced Lyrics**:
  - **Attached** panel under/over the HUD card (previous line, current line highlighted, upcoming lines), or **Subtitles** centered above the hotbar.
  - Timing offset slider if a song's lyrics are a little early/late.
  - Falls back to plain lyrics (estimated position, marked with `≈`) when synced ones don't exist.
- **Playback Controls**: play/pause, next, previous (and volume on macOS/Linux or in Spotify API mode).
- **Configurable Keybinds**, **`/music` chat commands** (`/spotify` still works), and a full **settings screen** (ModMenu).

---

## HUD Preview

```text
┌─────────────────────────────────────────────────────────────┐
│ ┌───────┐  ▶ Playing · Apple Music                          │
│ │ ALBUM │  Song Title                                       │
│ │  ART  │  Artist Name • Album Name                         │
│ └───────┘  ━━━━━━━━━━━━━━━●────────────────────────────────  │
├─────────────────────────────────────────────────────────────┤
│   (previous line, dimmed)                                   │
│ ▌ The line being sung right now, highlighted                │
│                                                             │
│   (next line, dimmed)                                       │
└─────────────────────────────────────────────────────────────┘
```

The overlay anchors to any of the 4 screen corners (`TOP_LEFT`, `TOP_RIGHT`, `BOTTOM_LEFT`, `BOTTOM_RIGHT`) with adjustable offsets, scaling, and opacity.

---

## Requirements

| Requirement | Supported Version | Notes |
| :--- | :--- | :--- |
| **Minecraft** | `26.3` | Client-side only |
| **Fabric Loader** | `>= 0.19.0` | Standard Fabric installation |
| **Fabric API** | Latest for 26.3 | Required for HUD render callbacks |
| **Java Runtime** | `Java 25+` | Required for Minecraft 26.x |
| **ModMenu** | `21.0.0+` *(Optional)* | Enables in-game configuration UI from the mods list |
| **Music app** | Spotify or Apple Music desktop app (Free or paid) | Anything that shows up in your OS media controls also works (browsers, Cider, etc.) |
| **Windows** | Windows 10 1809+ / 11 | Uses the built-in Windows PowerShell 5.1 (already installed) |
| **macOS** | Spotify or Music app | macOS will ask once to let Minecraft control the app |
| **Linux** | `playerctl` installed | e.g. `sudo apt install playerctl` |

---

## Player Installation

1. Install **Fabric Loader** for Minecraft 26.3 from [fabricmc.net](https://fabricmc.net/).
2. Download the latest **Fabric API** for Minecraft 26.3 from Modrinth or CurseForge.
3. Download `music-mc-1.3.0.jar` from the **Releases** tab of this repository.
4. Place both `.jar` files into your `.minecraft/mods/` folder:
   - **Windows**: `%appdata%\.minecraft\mods\`
   - **macOS**: `~/Library/Application Support/minecraft/mods/`
   - **Linux**: `~/.minecraft/mods/`
5. Launch Minecraft using your Fabric profile.

---

## How It Works (No Login)

1. Install the mod and launch Minecraft.
2. Start playing music in **Spotify** or **Apple Music** on the same computer.
3. That's it. The HUD picks it up within a second.

Under the hood the mod asks your operating system what's playing:

| OS | How | Notes |
| :--- | :--- | :--- |
| **Windows** | System Media Transport Controls (the media overlay you see when pressing volume keys), via a small hidden Windows PowerShell helper | Works with Spotify, the Apple Music app, iTunes, browsers. Volume keys aren't supported here (Windows doesn't expose per-app volume through media controls). |
| **macOS** | AppleScript (`osascript`) talking to the Spotify / Music apps | First use shows a one-time "Minecraft wants to control Spotify/Music" prompt. Click **OK**. |
| **Linux** | MPRIS via `playerctl` | Works with Spotify, Cider (Apple Music), browsers and most players. |

**Choosing an app:** by default the mod follows whatever is playing, preferring music apps over browsers. To always follow one app, open the settings and set **Follow** to *Spotify* or *Apple Music*, or run `/music app spotify` / `/music app apple`.

---

## Lyrics

Lyrics are on by default and come from [LRCLIB](https://lrclib.net) (free, no account). The mod looks them up by title, artist, album and track length, and caches them while you play.

- **Style**: *Attached to HUD* (panel next to the card) or *Subtitles* (centered above the hotbar, adjustable size).
- **Upcoming Lines**: how many lines after the current one to show (0–3).
- **Timing**: nudge lyrics earlier/later if they're consistently off (`/music lyrics offset 300` = show 0.3s earlier).
- **Wrong lyrics?** Use *Fetch again* in Lyrics Settings or `/music lyrics refresh`.
- Instrumentals show `♪ Instrumental ♪`; songs LRCLIB doesn't have show "No lyrics found".

---

## Optional: Spotify Web API Mode

The original Web API mode is still here for people who want it, e.g. to see/control Spotify playing on your **phone or a smart speaker** (system media only sees the computer Minecraft runs on). It needs your own Spotify developer app:

> Spotify now limits developer-mode apps to a handful of allow-listed users and requires the developer to have Premium, which is why this is no longer the default.

1. Go to the [Spotify Developer Dashboard](https://developer.spotify.com/dashboard), click **Create an App**.
2. Set **Redirect URI** to exactly `http://127.0.0.1:8888/callback` and select **Web API**.
3. Copy the **Client ID** (no client secret needed; PKCE is used).
4. In-game: open settings, click **Source** until it says *Spotify Web API* (or run `/music source spotify`), paste the Client ID and click **Connect Spotify**.
5. Approve in the browser and return to Minecraft.

Playback controls in this mode require Spotify Premium. Switch back any time with `/music source system`.

---

## Controls & Commands

### In-Game Keybinds
By default, keybinds are left **Unassigned** so they do not conflict with your existing gameplay controls.

To assign them:
1. Open **Options** -> **Controls** -> **Key Binds**.
2. Scroll to the **Music MC** section.
3. Bind keys for:
   - `Play / Pause`
   - `Next Track`
   - `Previous Track`
   - `Toggle Music HUD`
   - `Toggle Lyrics`
   - `Volume Up (+5%)` / `Volume Down (-5%)` *(macOS, Linux and Spotify API mode)*

### Chat Commands
All commands run entirely on the client. `/spotify` works as an alias for `/music`.

| Command | Description |
| :--- | :--- |
| `/music status` | Prints the current track, progress and app to chat |
| `/music play` / `pause` | Resumes / pauses playback |
| `/music next` / `previous` | Skips tracks |
| `/music hud` | Toggles the HUD overlay |
| `/music lyrics` | Toggles lyrics |
| `/music lyrics style` | Switches between *Attached* and *Subtitles* |
| `/music lyrics offset <ms>` | Shift lyric timing (positive = earlier, `0` resets) |
| `/music lyrics refresh` | Clears the lyrics cache and fetches again |
| `/music app <any\|spotify\|apple>` | Which app to follow in system media mode |
| `/music source <system\|spotify>` | No-login system media (default) or Spotify Web API |
| `/music connect` | Spotify Web API mode only: opens the OAuth login |
| `/music logout` | Disconnects the Spotify Web API and deletes stored tokens |

---

## Configuration

Configuration is saved in `.minecraft/config/spotifymc/config.json`. You can modify it directly or through the in-game GUI:

```json
{
  "sourceMode": "SYSTEM",
  "preferredApp": "ANY",
  "matchAppColor": true,
  "onlineArtworkLookup": true,
  "lyricsEnabled": true,
  "lyricsStyle": "ATTACHED",
  "lyricsUpcomingLines": 1,
  "lyricsOffsetMs": 0,
  "lyricsScale": 1.0,
  "hudEnabled": true,
  "showAlbumArt": true,
  "autoHideWhenInactive": false,
  "anchor": "TOP_LEFT",
  "offsetX": 10,
  "offsetY": 10,
  "hudScale": 1.0,
  "hudOpacity": 0.85,
  "pollingIntervalSec": 2,
  "accentColor": -14829228,
  "clientId": "YOUR_SPOTIFY_CLIENT_ID"
}
```

### Config Options:
- **`sourceMode`**: `SYSTEM` (no login, default) or `SPOTIFY_API`. Existing users who had already connected the Spotify API are kept on `SPOTIFY_API` when upgrading.
- **`preferredApp`**: `ANY`, `SPOTIFY` or `APPLE_MUSIC` (system media mode).
- **`matchAppColor`**: Use Spotify green / Apple Music red as the accent instead of `accentColor`.
- **`onlineArtworkLookup`**: Look up cover art on the iTunes Search API when the player doesn't provide it.
- **`lyricsEnabled` / `lyricsStyle`**: Lyrics on/off; `ATTACHED` or `SUBTITLES`.
- **`lyricsUpcomingLines`**: Lines shown after the current one (`0`–`3`).
- **`lyricsOffsetMs`**: Lyric timing shift in ms (`-5000`–`5000`, positive = earlier).
- **`lyricsScale`**: Subtitle text size (`0.5x`–`2.0x`).
- **`hudEnabled`**: Master toggle for HUD rendering (card and lyrics).
- **`showAlbumArt`**: Show album artwork.
- **`autoHideWhenInactive`**: Hide the overlay unless something is playing.
- **`anchor`**: `TOP_LEFT`, `TOP_RIGHT`, `BOTTOM_LEFT`, or `BOTTOM_RIGHT`.
- **`offsetX` / `offsetY`**: Pixel margin from the chosen anchor corner.
- **`hudScale`**: HUD sizing factor (`0.5x` to `2.0x`).
- **`hudOpacity`**: Background opacity (`20%` to `100%`).
- **`pollingIntervalSec`**: Spotify Web API polling frequency (`1` to `10`). System media mode polls locally every second.

---

## Technical Architecture & Security

```
music-mc/  (repo: spotify-mc)
├── .gitattributes                      # Line endings & binary rules
├── .gitignore                          # Excludes credentials, build caches, and jars
├── LICENSE                             # MIT License
├── README.md                           # Project documentation
├── build.gradle                        # Fabric Loom build script
├── gradle.properties                   # Project and dependency versions
├── settings.gradle                     # Project settings
├── gradlew & gradlew.bat               # Gradle wrapper execution scripts
├── gradle/wrapper/
│   ├── gradle-wrapper.jar
│   └── gradle-wrapper.properties
└── src/
    ├── main/
    │   ├── resources/
    │   │   ├── fabric.mod.json         # Mod metadata & entrypoints
    │   │   └── assets/spotifymc/
    │   │       ├── icon.png            # 64x64 Mod icon
    │   │       ├── lang/en_us.json     # Localization translations
    │   │       └── scripts/smtc-bridge.ps1 # Windows media-session helper (PowerShell 5.1)
    │   └── java/com/spotifymc/
    │       ├── SpotifyMod.java         # Mod entrypoint & client lifecycle
    │       ├── auth/
    │       │   ├── PkceUtils.java      # RFC 7636 SHA-256 verifier & challenge
    │       │   ├── LocalCallbackServer.java # Ephemeral 127.0.0.1:8888 listener
    │       │   ├── TokenStorage.java   # AES-GCM encrypted local credential store
    │       │   └── SpotifyAuthManager.java  # Token lifecycle & automatic background refresh
    │       ├── api/
    │       │   ├── SpotifyDevice.java  # Active device record
    │       │   ├── SpotifyPlaybackState.java # Extrapolated playback state
    │       │   └── SpotifyApiClient.java    # Non-blocking JDK HttpClient & 429 backoff
    │       ├── media/
    │       │   ├── MediaSource.java         # Common interface for playback sources
    │       │   ├── SystemMediaSource.java   # No-login source: picks the right OS player
    │       │   ├── SpotifyApiSource.java    # Optional Spotify Web API source
    │       │   ├── AppKind.java / PreferredApp.java # Spotify / Apple Music detection
    │       │   ├── ArtworkLookup.java       # iTunes Search fallback for cover art
    │       │   └── bridge/
    │       │       ├── WindowsSmtcBridge.java   # Windows media controls via PowerShell
    │       │       ├── MacScriptingBridge.java  # macOS Spotify/Music via AppleScript
    │       │       └── LinuxPlayerctlBridge.java # Linux MPRIS via playerctl
    │       ├── lyrics/
    │       │   ├── LyricsService.java       # LRCLIB lookup, matching & caching
    │       │   ├── LrcParser.java           # Synced LRC parser
    │       │   └── Lyrics.java              # Lyrics model & current-line lookup
    │       ├── playback/
    │       │   └── SpotifyPlaybackManager.java # Polling coordinator for the active source
    │       ├── hud/
    │       │   ├── AlbumArtCache.java  # Asynchronous LRU texture loader (http + local files)
    │       │   ├── LyricsRenderer.java # Attached panel & subtitle lyric styles
    │       │   └── SpotifyHudRenderer.java # Glassmorphic HUD renderer
    │       ├── keybinds/
    │       │   └── SpotifyKeybindHandler.java # Keybind triggers & repeat filters
    │       ├── commands/
    │       │   └── SpotifyCommands.java     # Fabric client command registrations
    │       └── config/
    │           ├── SpotifyConfig.java       # Config serialization & defaults
    │           ├── SpotifyConfigScreen.java # Main settings screen (source, HUD)
    │           ├── LyricsConfigScreen.java  # Lyrics settings screen
    │           └── ModMenuIntegration.java  # ModMenu API entrypoint
    └── test/java/com/spotifymc/
        ├── auth/PkceUtilsTest.java     # Validates RFC 7636 Appendix B test vector
        ├── auth/TokenStorageTest.java  # Validates AES-GCM encryption & redaction
        ├── lyrics/                     # LRC parsing & lyric matching tests
        └── media/                      # Player selection & OS bridge parsing tests
```

### Security Measures
1. **RFC 7636 PKCE Authentication**:
   - The Authorization Code flow with PKCE ensures code interception attacks are impossible without needing an embedded client secret.
2. **AES-GCM Local Credential Encryption**:
   - Spotify access and refresh tokens are stored in `credentials.json` encrypted with **AES-GCM (128-bit authentication tag)** using a local hardware-derived key.
3. **Sensitive Information Redaction**:
   - All token storage objects, HTTP logs, and debug strings redact authorization tokens as `[REDACTED]`. Tokens are never exposed in crash reports or logs.
4. **Git Protection**:
   - The repository's `.gitignore` explicitly prevents accidental commits of credentials, tokens, logs, and development runs.

---

## Building from Source

### Prerequisites
- **Java 25 JDK** or newer (e.g. [Eclipse Temurin 25](https://adoptium.net/temurin/releases/?version=25))
- **Git**

### Clone & Build
```bash
# Clone the repository
git clone https://gitea.jadonsandbox.com/jkortis/spotify-mc.git
cd spotify-mc

# Run tests
./gradlew test        # On Linux / macOS
.\gradlew.bat test    # On Windows

# Launch Minecraft development client
./gradlew runClient        # On Linux / macOS
.\gradlew.bat runClient    # On Windows

# Build the distributable mod JAR
./gradlew build        # On Linux / macOS
.\gradlew.bat build    # On Windows
```

The output JAR will be placed in `build/libs/music-mc-1.3.0.jar`.

---

### Automatic Builds (Gitea Actions)
Every push to any branch (and every pull request) builds the mod automatically via `.github/workflows/build.yml`:

1. Open the repository's **Actions** tab and click the latest **Build** run.
2. Download the `music-mc-<version>+build.<commit>` artifact under **Artifacts** (it contains the jar).

Dev builds are versioned like `1.3.0+build.c4419df` so you can tell them apart. Pushing a `v*` tag still runs `build-release.yml`, which publishes a proper release.

---

## Troubleshooting & FAQ

### HUD says "Waiting for music"
- Make sure the song is actually playing in the **desktop app** on the same computer (Spotify / Apple Music). On Windows, check that it appears in the media overlay when you press a volume key.
- If you set **Follow** to one app, the other app is ignored.

### HUD says "Unavailable"
- **Windows**: the helper uses `powershell.exe` (Windows PowerShell 5.1, built into Windows 10/11). If your antivirus or a company policy blocks PowerShell, system media mode can't run; use Spotify Web API mode instead. To test the helper by hand, run `powershell -ExecutionPolicy Bypass -File "%appdata%\.minecraft\config\spotifymc\smtc-bridge.ps1"`, then type `state` and press Enter.
- **macOS**: allow Minecraft (or `java`) under *System Settings → Privacy & Security → Automation*.
- **Linux**: install `playerctl`.

### Lyrics are missing or wrong
- LRCLIB is community-run; some songs aren't there yet, and very new releases may take a while.
- Try `/music lyrics refresh`. If the timing is consistently off for a song, use the **Timing** slider.

### Spotify Web API mode

#### HTTP 403 Forbidden ("Spotify Premium is required for playback controls")
- **Cause**: Spotify API policies restrict playback modification endpoints (`play`, `pause`, `next`, `previous`, `volume`) to accounts with an active **Spotify Premium** subscription.
- **Solution**: Free Spotify accounts can still view song titles, artist names, album art, and progress bar. If you have Premium, verify you are logged into the correct Spotify account in your browser.

#### HTTP 404 / 204 ("No Active Player")
- **Cause**: Spotify has no active playback session on any device.
- **Solution**: Open the Spotify Desktop app, Web player, or mobile app and start playing a track. Once Spotify registers an active player, the mod will instantly lock on.

#### Browser Redirect / Port 8888 Conflict
- **Cause**: Another service is using port 8888, or the redirect URI in the developer dashboard has a typo.
- **Solution**:
  - Verify that the redirect URI in your [Spotify Developer Dashboard](https://developer.spotify.com/dashboard) is **exactly** `http://127.0.0.1:8888/callback` (case-sensitive, no spaces, no trailing slash).
  - Ensure no other local proxy or web server is holding port 8888.

#### HTTP 429 Too Many Requests ("Rate Limited")
- **Cause**: Spotify Web API temporary rate limit reached.
- **Solution**: Music MC features built-in adaptive rate-limiting backoff. It automatically reads the `Retry-After` header and pauses polling until the cooldown expires.

---

## License

This project is licensed under the [MIT License](LICENSE).


## Custom lyrics modes (custom build)

This customized build adds two extra lyric styles in the lyrics settings/command cycle:
- **Cinematic** — larger, centered lyric text.
- **Sky Lyrics** — floating lyric text in the upper part of the game view.

Local LRC fallback: put synced `.lrc` files in `config/spotifymc/lyrics/`. File names can be `Artist - Title.lrc` or `Title.lrc`. Online LRCLIB lookup is attempted first; when it returns no lyrics, the mod checks the local folder. LRC timestamps are used for synchronization.
