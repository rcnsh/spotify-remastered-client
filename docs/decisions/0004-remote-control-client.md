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
artist pages show "In your library" instead of Popular; folders and pins are the app's own,
kept on the phone; playlists by other people get their track list from App Remote (open
question 2), else header + play/shuffle only.

## Open questions (resolve before accepting)

1. **Answered: stock Spotify can't log in on the emulator** (device integrity). Development
   needs a physical phone with stock Spotify logged in. Optional: confirm upstream's
   finding by trying the re-signed build on that phone with a throwaway account.
2. **Answered (2026-10-23): yes, App Remote's ContentApi lists any playlist's tracks.**
   `getChildrenOfItem` with a bare `ListItem` built from the playlist uri (`hasChildren` true),
   tried on the phone (App Remote SDK 0.8.0) from Settings → Developer → Open a Spotify uri →
   "List children": one of the user's own playlists gave `total 82`, 50 tracks per page in
   ~0.7 s; Today's Top Hits (`37i9dQZF1DXcBWIGoYBM5M`, Spotify's own) gave `total 50`, 50 tracks
   in ~1.2 s. Each child has the track uri, title, artists as one string, artwork (an App Remote
   image id) and whether it's playable; no durations, album, or artist/album ids. It's a read:
   nothing plays. So other people's playlists now show their track list, read through App Remote
   when the Web API won't share it (falling back to header + play/shuffle when App Remote isn't
   connected or fails). Tracks listed this way have no "Go to album/artist".
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
