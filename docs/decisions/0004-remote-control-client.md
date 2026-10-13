# 0004: Remote-control client instead of a client inside Spotify

- Status: accepted (2026-10-13, user go-ahead after the real-phone test)
- Date: 2026-10-13
- Supersedes: 0001 (0002 and 0003 lapse with it)

## Context

0001 depends on a patched, re-signed Spotify being able to log in. It can't:

- Confirmed on a real Pixel 10 Pro: our re-signed 9.1.80.2221 build rejects a throwaway
  account's correct password, which official Spotify accepts. (Emulators reject stock
  Spotify too, so they can't be used for this.) Upstream reports the same
  ([#4](https://github.com/spicetify/morphe-patches/issues/4),
  [#5](https://github.com/spicetify/morphe-patches/issues/5)); its maintainer says Spotify's
  backend now requires a Play Integrity verdict, which is bound to the signing certificate,
  and that client-side spoofing was tried and rejected.
- The verdict is signed by Google about the installed app's certificate. Without Spotify's
  key, getting past it means making the device lie to Google, which is detection evasion
  and out of scope. Details were in the old project's RESEARCH.md, removed with the patch tooling.

The user's goal is unchanged: a redesigned Spotify they use instead of the stock UI, with
stock Spotify installed and logged in (Premium) but never opened.

## Decision

Build a separate Android app that drives the installed, unmodified Spotify:

- **App Remote SDK** (official, talks to the Spotify app's background service): player
  state, artwork, play by URI, pause/skip/seek, shuffle/repeat, add to queue, like.
- **Web API** with the user's own Development Mode app (owner has Premium, ≤ 5 users):
  library, playlists, albums, artists, search, recently played, top items, queue (read),
  Connect devices, volume.

Stock Spotify keeps playback, DRM, downloads, MediaSession/notification and Connect.
Nothing is patched or re-signed.

## What the APIs allow (checked 2026-10-13)

Development Mode, after the
[Feb 2026 changes](https://developer.spotify.com/documentation/web-api/tutorials/february-2026-migration-guide)
and the [Nov 2024 changes](https://developer.spotify.com/blog/2024-11-27-changes-to-the-web-api):

| Works | Gone or never there |
|---|---|
| Search (10 per page, paginate) | Browse categories, new releases, featured playlists |
| Album, artist, track (single fetches) | Batch fetches (`/tracks?ids=` …) |
| Artist albums ("Discography", "Appears on"; 10 per page) | Artist top tracks, related artists |
| Your playlists, saved albums/tracks/shows, followed artists | **Track lists of playlists you don't own or collaborate on** |
| Create/edit/reorder your own playlists | Recommendations, audio features/analysis |
| Recently played, top artists/tracks | Popularity, follower counts, label, user email/country |
| Player, queue (read), devices, volume (Premium) | Queue remove/reorder (never existed) |
| Save/remove via `PUT/DELETE /me/library` | Playlist folders (never exposed) |
| | Lyrics, Canvas, DJ, Jam, Wrapped (never public) |

Design response (see `docs/design/mockups/`): Home is built from the user's own listening;
artist pages show "In your library" instead of Popular; folders are the app's own;
playlists by other people show header + play/shuffle only.

## Open questions (resolve before accepting)

1. **Answered: stock Spotify can't log in on the emulator** (device integrity). Development
   needs a physical phone with stock Spotify logged in. Optional: confirm upstream's
   finding by trying the re-signed build on that phone with a throwaway account.
2. **App Remote ContentApi:** can `getChildrenOfItem` list tracks of an arbitrary playlist
   URI? If so, it fills the "playlists by other people" gap. Spike it.
3. **Latency:** measure App Remote command round-trips, cold-connect time, and Web API page
   loads (targets in PLAN once rewritten).
4. App Remote is labelled Beta by Spotify; confirm it still connects to 9.1.x.

## Consequences

- No login, signing or integrity problems: Spotify is genuine and does its own auth.
- Most of Phase 0–1 (patch build, esperanto recorder, proto recovery) stops being needed;
  the RE tooling stays useful for reference only.
- Feature set is capped by Spotify's public APIs, and Spotify can tighten them again (it
  did in 2024 and 2026). Keep each feature behind a capability check.
- Spotify's notification and lock-screen player remain Spotify's.
