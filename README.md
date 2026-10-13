# Spotify Remastered (client)

A redesigned Android client for Spotify, for personal use. It doesn't replace or modify
Spotify: it drives the installed, unmodified Spotify app.

- Playback, artwork and player state come from Spotify's official **App Remote SDK**, which
  talks to the Spotify app running on the phone.
- Library, playlists, albums, artists and search come from the **Spotify Web API**, using
  the user's own Spotify developer app (PKCE sign-in).
- Spotify keeps doing playback, DRM, downloads, the notification and Connect.

Built with Kotlin and Jetpack Compose.

## Planned: synced lyrics

Time-synced lyrics on the Now Playing screen, from the Spicy Lyrics developer API
(`GET /v1/lyrics/{trackId}`). Lyrics are shown in the app only, with the provider and any
community uploader credited on screen next to them, and cached for at most 30 days.

## Status

Source code is being moved into this repository. Not affiliated with Spotify or Spicy Lyrics.
