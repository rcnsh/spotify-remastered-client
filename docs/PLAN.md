# Plan

Where the client is. Tick tasks here in the same change that finishes them.

## Built
- **Screens** (ADR 0004): every mockup screen: Home, Search, Library, Liked Songs,
  playlist, album, artist, Now Playing, queue, sheets, and a full Settings screen
  (account, playback, library, storage, developer tools). Web API with PKCE sign-in;
  App Remote for playback.
- **Lyrics** (ADR 0005): a card on Now Playing that opens a full-screen view (word-by-word
  fill, tap a line to seek, interlude dots), attribution, a 30-day cache, Settings → Lyrics.
  Checked on the emulator preview; not yet with real playback on the phone.
- **Listening history** (ADR 0006), from the music-warehouse Worker:
  - [x] Warehouse API layer, Settings section, capability gating (#1)
  - [x] History, On repeat, Stats, "Your plays", On this day (#2)
  - [x] Native Wrapped story with App Remote soundtrack and PNG share (#3)
  - [x] Wrapped MP4 export (`MediaCodec`, optional user-picked audio) (#3)
  - [x] Verified on the phone: History, Stats, Wrapped story and export

## Open
- [ ] Media notification (ADR 0007): verified on the phone (card shows; play/pause, next,
      previous, like work; a tap opens Now Playing; controls keep working with Remastered
      swiped from recents). Left: Spotify keeps playing with its own notification channel off.
- [ ] Wrapped: check Unmute resumes at the hook on the phone (fix only covered by tests).
- [ ] Screen cache (`ScreenCache`, keeps loaded screens while moving between them):
      not yet verified on the phone.
- [ ] Lyrics with real playback on the phone.
- [ ] Release signing: release builds compile but are unsigned; the phone runs the debug
      build, and switching keys means an uninstall (which logs Spotify out of the client).
