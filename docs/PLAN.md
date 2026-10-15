# Plan

Where the client is. Tick tasks here in the same change that finishes them.

## Built
- **Screens** (ADR 0004): every mockup screen: Home, Search, Library, Liked Songs,
  playlist, album, artist, Now Playing, queue, sheets, and a full Settings screen
  (account, playback, library, storage, developer tools). Web API with PKCE sign-in;
  App Remote for playback. Screen cache (`ScreenCache`) verified on the phone.
- **Lyrics** (ADR 0005): a card on Now Playing that opens a full-screen view (word-by-word
  fill, tap a line to seek, interlude dots), attribution, a 30-day cache, Settings → Lyrics.
  Verified on the phone with real playback.
- **Listening history** (ADR 0006), from the music-warehouse Worker:
  - [x] Warehouse API layer, Settings section, capability gating (#1)
  - [x] History, On repeat, Stats, "Your plays", On this day (#2)
  - [x] Native Wrapped story with App Remote soundtrack and PNG share (#3)
  - [x] Wrapped MP4 export (`MediaCodec`, optional user-picked audio) (#3)
  - [x] Verified on the phone: History, Stats, Wrapped story and export, Unmute resuming
        at the hook
  - [x] Sign in with Spotify for the warehouse (its `/auth/login` with PKCE, redirect to
        `app.remastered://warehouse-auth`), next to the pasted token. Verified end to end
        on the phone
  - [x] Home ranked from the history (warehouse `/api/home`): shortcuts by frecency and
        time of day, Jump back in, Around this time, Rediscover; recently played as fallback.
        Verified on the phone: all four rows load, nothing repeats, a card opens its album
- **Media notification** (ADR 0007): verified on the phone (card shows; play/pause, next,
  previous, like work; a tap opens Now Playing; controls keep working with Remastered
  swiped from recents; Spotify keeps playing with its own notification channel off).
- **Rate limits**: Liked Songs saved on the phone (a start fetches only new likes), and a
  429 blocks its endpoint until `Retry-After` passes, across restarts. Verified on the phone.

## Open
- [x] Playlist reorder: long-press a song in a playlist you can edit (own order, no filter)
      and drag it; the list scrolls at the edges. Verified on the phone: drags up and down,
      the order survives a reload from Spotify, edge scrolling both ways keeps the drag.
- [ ] Edit playlist details (owner only): name, description, and a new cover from the photo
      picker. Covers need the `ugc-image-upload` scope, added now: sign in to Spotify again to
      get it (until then the sheet says so). Not yet verified on the phone.
- [x] Podcasts: saved shows in the Library (Podcasts filter), a show screen with episodes
      (paged as you scroll), "Go to show" in an episode's menu. Verified on the phone
      (playing an episode from the show screen not tried).
- [ ] App-owned Library folders and pins (ADR 0004).
- [ ] Spike App Remote `getChildrenOfItem` for track lists of other people's playlists
      (ADR 0004, open question 2).

## Deferred
- Release signing: release builds compile but are unsigned; the phone runs the debug build
  (debug key), which is fine for now. Switching keys means an uninstall (which logs Spotify
  out of the client).
