# CLAUDE.md: Spotify Remastered

Read this first, then `docs/PLAN.md` for where the project is. Keep both current: when a
task in PLAN.md is done, tick it in the same change.

## What this project is

A redesigned Spotify client for Android, for personal use: **a separate app that drives
the installed, unmodified Spotify** (ADR 0004). Spotify keeps login, playback, DRM,
downloads, MediaSession/notification and Connect. This app owns the UI and talks to:

- **App Remote SDK**: player state, artwork, play by URI, transport controls, queue add, like.
- **Spotify Web API** (the user's own Development Mode app, PKCE): library, playlists,
  albums, artists, search, recently played, top items, devices. See ADR 0004's table for
  what the API no longer allows.
- **Spicy Lyrics API**: synced lyrics (ADR 0005).
- **music-warehouse** (the user's own Cloudflare Worker, `~/dev/music-warehouse`): full
  play history, stats and Wrapped (ADR 0006).

Decisions: `docs/decisions/`. Design: `docs/design/` (mockups the screens follow).

## Layout

Kotlin + Jetpack Compose, one module `app/`, package root `app.remastered`:
- `spotify/auth`, `spotify/web`, `spotify/remote`: PKCE, Web API, App Remote.
- `core/`: player, catalog, settings, lyrics, models; `core/warehouse` (API and state),
  `core/wrapped` (timeline, motion, video export). `spicylyrics/`: lyrics API.
  `warehouse/`: warehouse wiring.
- `ui/`: navigation, screens, player, lyrics, history, stats, wrapped, components, theme.
- `scripts/`: `fetch-spotify-sdk.sh` (App Remote + Auth AARs into `app/libs`, gitignored),
  `emulator.sh`.

`local.properties` holds the Spotify app settings, Spicy Lyrics key, and warehouse URL and
token. It's gitignored: never commit it.

Related repos in `~/dev`:
- `music-warehouse`: the Worker the client reads. Uses Wrangler (`wrangler.jsonc`); keep it.
- `music-warehouse-wrapped`: the web Wrapped (Remotion), reference for the native port.
- `music-warehouse-gui`: macOS app (Rust/GPUI) over the same API, reference for Stats.

## Rules

### Code
- Match the existing code: comment density, naming, Compose patterns, `Palette`/`Type`
  from `ui/theme`. Keep screens faithful to `docs/design/mockups/`.
- Every feature that depends on an API Spotify can take away, or on the warehouse, sits
  behind a capability check and hides itself when unavailable. Never crash or show a
  blank screen for a missing capability.
- Never block the main thread; network and disk on coroutines.
- Add unit tests in the existing style (fakes, MockWebServer fixtures, no Spotify
  binaries, no personal data in fixtures).

### Warehouse
- The phone holds the **READ_TOKEN only**, never ADMIN_TOKEN. READ_TOKEN can compute only
  the preset Wrapped ranges (ADR 0006).
- Response shapes are kept by hand on both sides: change the Worker and the client
  together, and decode leniently in the client.
- Don't deploy the Worker or apply remote D1 migrations without the user's go-ahead.

### Safety, legal, privacy
- This repo is public. Never commit APKs, keystores, tokens or `local.properties`. Never
  log access tokens, the warehouse token, or `Authorization` headers.
- Do not bypass Premium, free-tier limits, ads, or DRM; do not extract audio. Wrapped
  exports never contain Spotify audio.

### Verification
- "Done" means verified on a device, not just compiled. Report exactly what was run and
  seen. If something wasn't tested (e.g. App Remote playback), say so.
- App Remote and anything logged in need the **physical phone**: emulators fail Play
  Integrity, so stock Spotify can't log in there.
- Don't uninstall the client or Spotify on the phone; that logs the user out. The phone
  runs the debug build (debug key); install over it with `installDebug` / `adb install -r`.

## Commands

```sh
# JDK 21
scripts/fetch-spotify-sdk.sh
./gradlew :app:testDebugUnitTest :app:installDebug
./gradlew :app:assembleRelease            # minified; unsigned

# Debug previews (no sign-in needed, work on the emulator)
adb shell am start -S -n app.remastered/.MainActivity --es lyrics_preview spotify:track:<id>
adb shell am start -S -n app.remastered/.MainActivity --es wrapped_preview 2025-01-01:2025-12-31

adb shell uiautomator dump /sdcard/u.xml && adb shell cat /sdcard/u.xml   # inspect UI
adb exec-out screencap -p > /tmp/s.png                                    # screenshot
scripts/emulator.sh [--headless]          # boot Pixel_10_Pro (Android 17, arm64)

# Warehouse
cd ~/dev/music-warehouse && npm test && npx tsc --noEmit
```

## Environment facts and gotchas

- macOS, Apple Silicon. Temurin 21 at `/Library/Java/JavaVirtualMachines/temurin-21.jdk`.
  Android SDK at `~/Library/Android/sdk`. `adb` and `emulator` are not on PATH; use
  `~/Library/Android/sdk/platform-tools/adb`. With the phone and an emulator both
  attached, pass `-s <serial>` (or `-d` / `-e`).
- Shell is zsh: an unquoted word starting with `=` (like `=====`) errors; `ls` output
  can be unreliable in tool calls, so prefer `/bin/ls` or `find`.
- The physical phone (Pixel 10 Pro, Spotify logged in) is the test device. When several
  agents share it, take a lock first:
  `until mkdir /tmp/remastered-phone.lock 2>/dev/null; do sleep 20; done`, and `rmdir` it
  when done.
- AVDs: `Pixel_10_Pro` (API 37.1, 16 KB pages, Play), `Pixel_5`. All images are 64-bit
  only. Use `-gpu swiftshader_indirect`. The emulator's software H.264 encoder overshoots
  the export bitrate; judge export size on the phone.
- Debug preview extras are only read on a fresh launch: use `am start -S`.
- Logging in requires the user. Never ask for Spotify credentials in chat.
- The warehouse runs at `https://music-api.rcn.sh`; its WAF rejects requests without an
  `Authorization` header. Tokens for local dev are in `~/dev/music-warehouse/.dev.vars`
  (gitignored): read them in shell, never print them.
