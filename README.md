# Spotify Remastered (client)

A redesigned Android client for Spotify, for personal use. It doesn't replace or modify
Spotify: it drives the installed, unmodified Spotify app.

- Playback, artwork and player state come from Spotify's official **App Remote SDK**, which
  talks to the Spotify app on the phone.
- Library, playlists, albums, artists and search come from the **Spotify Web API**, using your
  own Spotify developer app (PKCE sign-in).
- **Synced lyrics** come from the [Spicy Lyrics developer API](https://developers.spicylyrics.org):
  - a card on Now Playing that opens a full-screen view;
  - words light up as they're sung, and tapping a line seeks there.
  - The provider and the people who synced the lyrics are credited under them.
  - Responses are cached for at most 30 days.
- **Listening history** (optional) comes from your own
  [music-warehouse](https://github.com/rcnsh/music-warehouse), a Cloudflare Worker that keeps
  every play: History, Stats and Wrapped. Set its address and token in Settings → Listening
  history; the token is stored encrypted on the phone. Without one, those features stay hidden.
  - History: every play, newest first, by day; jump to a date, pull to refresh. Plays newer
    than the warehouse's last poll come from Spotify's recently-played.
  - Home's "On repeat" and "On this day", and "Your plays" on artist pages and in the song menu.
  - Stats: plays per day (7 days, 30 days, a year or any range), top artists, on repeat,
    streaks and listening by hour, top artists and songs per quarter and year, eras, top albums.
  - Wrapped: a native port of [music-warehouse-wrapped](https://github.com/rcnsh/music-warehouse-wrapped)'s
    story for each year (and the year so far): the same 14 slides, timing and motion, drawn
    frame by frame in Compose. Tap, hold to pause, swipe down to close; each music slide plays
    its song through Spotify from the chorus; share a frame or the summary card as a PNG, or
    export the whole story as an MP4, silent or over a song file of your own.
- Spotify keeps doing playback, DRM, downloads, the notification and Connect.

Built with Kotlin and Jetpack Compose. Not affiliated with Spotify or Spicy Lyrics.

## Build

Needs JDK 21 and the Android SDK (compileSdk 37). On the phone you need Spotify installed and
logged in. The emulator can't run a logged-in Spotify, so App Remote only connects on a real
device.

1. `scripts/fetch-spotify-sdk.sh` downloads Spotify's App Remote and Auth AARs into `app/libs/`
   and checks their SHA-256s. They are Apache-2.0, published only as GitHub release assets, and
   not committed here.
2. Create `local.properties`. It is gitignored: never commit it.

   ```properties
   sdk.dir=/path/to/Android/sdk
   # From developer.spotify.com/dashboard. Add the redirect URI there too.
   spotify.clientId=…
   spotify.redirectUri=app.remastered://callback
   # Optional. Leave unset to use PKCE only.
   spotify.clientSecret=
   # Spicy Lyrics publishable key (sl_pk_…) with "Allow requests with no Origin header" on.
   # The build refuses a secret key (sl_sk_…). Without a key the app runs without lyrics.
   spicylyrics.key=
   # Optional, debug builds only: start with this music-warehouse already set up (Settings →
   # Listening history can change or remove it). Use the READ_TOKEN. Release builds never embed these.
   warehouse.url=https://music-api.example.com
   warehouse.readToken=
   ```
3. `./gradlew :app:testDebugUnitTest :app:installDebug`

Each person who builds this needs their own Spotify developer app and their own Spicy Lyrics key.

## Checking lyrics without Spotify

Debug builds can show a track's lyrics on a local clock, for example on an emulator:

```sh
adb shell am start -n app.remastered/.MainActivity --es lyrics_preview spotify:track:<id>
```

In the app, the same preview is under Settings → Open a Spotify uri → Lyrics.

## Checking Wrapped without signing in

Debug builds can open a Wrapped story straight from the warehouse (the debug read token), with
no Spotify session: artist photos fall back to the web's, and there is no soundtrack.

```sh
adb shell am start -n app.remastered/.MainActivity --es wrapped_preview 2025-01-01:2025-12-31
```

`--ei wrapped_frame 423` opens it paused at a frame, `--es wrapped_dump 110,244` renders frames
to PNGs in the app's external files (`wrapped/`), and `--es wrapped_export silent` (or a song
file's uri) starts a video export. In the app it's under Settings → Wrapped preview.

## Docs

- `docs/decisions/`: why it works this way (ADRs).
- `docs/design/mockups/`: the design the screens follow.
- `docs/PLAN.md`: what is built and what is open.
- `scripts/emulator.sh`: boots the dev emulator, for previews that don't need Spotify.
