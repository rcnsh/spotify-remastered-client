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
