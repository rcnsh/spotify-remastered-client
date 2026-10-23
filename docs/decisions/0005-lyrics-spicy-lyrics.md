# 0005: Synced lyrics from the Spicy Lyrics API

- Status: accepted
- Date: 2026-10-13
- Builds on: 0004 (lyrics aren't in Spotify's public APIs)

## Context

Spotify never exposed lyrics publicly (0004's table). [Spicy Lyrics](https://github.com/Spikerko/spicy-lyrics)
is a Spicetify extension for the desktop app (TypeScript/React, AGPL-3.0), so it can't run in
our Android client. Its authors also run a [developer API](https://developers.spicylyrics.org) for
third-party apps: `GET https://api.spicylyrics.org/v1/lyrics/{spotify track id}`, with the
best sync per track (community, then Apple Music, then Spotify), as JSON:

- `Syllable`: word-timed lines with lead and background vocals and a second-singer flag.
- `Line`: line-timed.
- `Static`: no timing.

Times are in seconds.

Checked 2026-10-13 against the live API.

## Decision

- **Our own Kotlin/Compose code, written from the API docs.** No code is ported from the
  extension; it served as a behaviour reference only. Lyrics use the same Now Playing pattern
  as Spotify: a card below the player that opens a full-screen view.
- **A publishable key** (`sl_pk_…`) with "No origin header" allowed, in `local.properties`
  as `spicylyrics.key`. The build refuses a secret key (`sl_sk_…`), and the secret key is kept
  under a separate property that the build never reads.
- **We follow the API terms:**
  - **Attribution next to the lyrics wherever they show:** the provider always; for
    `spicy_lyrics` responses, also the maker and uploader, linked. It is built from each
    response, never hardcoded (`Attribution.credit()`).
  - **Cached for under 30 days:** "no lyrics" answers for 1 day, swept at startup.
  - **Shown only in the app:** no export or copy-out, with one exception (added 2026-10-23): a
    share card, an image of one to four lines the user picks, with no timing data and the credit
    printed on it. The terms (§5) forbid downloaders and handing out the response or sync data
    to keep, and allow "a user selecting lyrics on screen"; a short quote image is our reading
    of that line, not something the terms name, so drop the card if Spicy Lyrics objects.
  - **`Retry-After` is honoured.**
- **Personal use only** (the user's call), so lyric rights clearance is out of scope.

## Consequences

- **Limits:** the publishable key allows 30 requests per window (the window resets in under 30
  seconds). We send one per new song, and the cache absorbs repeats.
- **Timing accuracy** depends on App Remote's reported position. A user-set offset in
  Settings → Lyrics (±2 s) corrects any lag. Not yet measured on a phone.
- **Spicy Lyrics can change limits or suspend access at any time and gives no uptime promise.**
  Lyrics fail to a message; the rest of the app is unaffected.
- **Emulator testing:** debug builds open a lyrics preview on a local clock with
  `adb shell am start -n app.remastered/.MainActivity --es lyrics_preview spotify:track:<id>`.
