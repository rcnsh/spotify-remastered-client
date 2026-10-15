# 0008: One gate and a budget for every Web API request

- Status: proposed (built 2026-10-16, awaiting the phone test)
- Date: 2026-10-16
- Builds on: 0004 (the Web API through the user's own Development Mode app), 0006 (music-warehouse)

## Context

Spotify limits a Development Mode app per Spotify user, and the limit is shared by all of that
user's apps. On 2026-10-16 the client was reinstalled and, on first sign-in, fetched everything
at once: all 3,000 Liked Songs (60 requests), Home's recently played, top items and a lookup per
context (a few dozen, all in parallel), retrying each 429 up to three times. Within two minutes
both the client and the music-warehouse Worker got `429 QUOTA_EXCEEDED` with a `Retry-After` of
about 9 hours. The warehouse's poller can't read recently played while that holds, so plays can
be lost.

The client already remembered a long `Retry-After`, but per endpoint: every other path (each
`/artists/{id}`, `/playlists/{id}`) still sent its own request and got its own 429.

## Decision

- **One gate** (`WebApiGate`) that every Web API request goes through:
  - Any response with `Retry-After` (a 429, or a 5xx that says) pauses every request until then.
    The deadline is wall-clock time in SharedPreferences, so it outlives the process; a deadline
    more than a day out is capped. Resetting the app keeps it.
  - A pause of up to 30 s is waited out; a longer one fails each request at once, without
    sending it. A request is retried at most once after a 429, and only after a short pause.
  - At most 3 requests in flight, and a token bucket of 10 requests refilling at 20 per 30 s.
  - App Remote (playback and controls) doesn't go through it and keeps working while paused.
- **The UI counts down** instead of offering "Try again" while paused, and a long pause says it's
  Spotify's limit for developer apps, not a busy moment. Settings → Developer shows the pause.
- **A start fetches only what's on screen**, and remembers what it fetched:
  - Liked Songs: a start restores the saved list and catches up (one request), or on a first run
    fetches one page; more as Liked Songs scrolls, all of it only for an artist page, finding a
    song or Settings → Reload. The list lives in files, not the cache, so Android clearing the
    cache doesn't cost a full reload.
  - Home: an album context is its play's album (no lookup). Playlist and artist tiles are kept on
    disk for 7 days (misses for a day). Home refreshes every 5 minutes, not every minute.
  - Your Library is kept on disk and refreshed every 30 minutes, or after a change made in the app.

## Consequences

- A first run's ♥ marks cover only the songs loaded so far, until something loads them all.
- Library changes made in Spotify itself show up within 30 minutes rather than a minute.
- Playlist and artist names and art on Home can be up to a week old.
- A cold start that wants more than the bucket allows is slower to fill in, by design.
