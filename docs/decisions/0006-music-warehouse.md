# 0006: Listening history, stats and Wrapped from music-warehouse

- Status: accepted
- Date: 2026-10-13
- Builds on: 0004 (the Web API only knows the last 50 plays)

## Context

The Web API gives the client the last 50 plays (`recently-played`) and three ranked
top-item lists. Nothing older, and no Wrapped (0004's table). The user already runs
[music-warehouse](https://github.com/rcnsh/music-warehouse), a Cloudflare Worker that
polls their play history into D1 every 30 minutes and serves it over a small API
(`/api/plays`, `/api/daily`, `/api/top-artists`, `/api/stats/*`, `/api/wrapped`).
[music-warehouse-wrapped](https://github.com/rcnsh/music-warehouse-wrapped) turns
`/api/wrapped` into a Remotion story and MP4 in the browser.

## Decision

- **The client reads the warehouse directly** over HTTPS with a bearer token. A native app
  sends the header itself, so it needs none of the web app's same-origin proxy.
- **READ_TOKEN only on the phone.** It can't trigger polls, imports or backfills. To let it
  open Wrapped, the warehouse gains a fixed set of preset ranges (each closed calendar
  year, and the year so far up to the last complete week) that READ_TOKEN may compute, and
  the daily cron warms them. Arbitrary ranges stay ADMIN_TOKEN only.
- **New warehouse routes** for the client: `/api/wrapped/ranges`, `/api/artist`,
  `/api/track`, `/api/on-this-day`, and later `/api/home` (Home's rows, ranked from the
  history, since Spotify's own home isn't open to apps).
- **Features**, each hidden while the warehouse isn't configured:
  - full History, merged with `recently-played` for the plays newer than the last poll;
  - Home's "On repeat" from `/api/stats/on-repeat`, falling back to the Web API;
  - a Stats screen (daily plays, top artists by range, rhythm, top over time, eras, albums);
  - "Your plays" on artist and track pages, and "On this day" on Home;
  - Home's shortcuts, "Jump back in", "Around this time" and "Rediscover" from
    `/api/home`, falling back to `recently-played`;
  - **Wrapped as native Compose**, not a WebView. Every slide is a pure function of the
    frame number, as in Remotion, so the story can be scrubbed and exported to MP4 with
    `MediaCodec`. The three.js scenes become 2.5D Canvas/AGSL. While viewing, each slide's
    song plays through App Remote. Exports never carry Spotify audio (DRM); they are
    silent or use a file the user picks.
- Config: warehouse URL and token in Settings, token stored encrypted. Debug builds can
  seed both from `local.properties` (`warehouse.url`, `warehouse.readToken`).

## Consequences

- A second backend to keep in step. The client decodes leniently so additive server
  changes don't break it; the shapes are kept by hand on both sides, like the web app's
  `types.ts`.
- History lags up to 30 minutes behind; the `recently-played` merge covers the gap.
- New warehouse routes need a deploy (and any migration) before the client's features
  that use them work.
- Personal use only: the warehouse holds one person's history.
