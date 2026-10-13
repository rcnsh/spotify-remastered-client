# 0007: Remastered's own media notification

- Status: proposed (built 2026-10-14, awaiting the phone test)
- Date: 2026-10-14
- Amends: 0004 ("Spotify's notification and lock-screen player remain Spotify's")

## Context

The goal of 0004 is stock Spotify installed but never opened. Its media notification undercuts
that: the lock screen, Quick Settings and the shade show Spotify's player, and tapping it opens
Spotify. Remastered also had nothing keeping its process alive once its window closed, so App
Remote only stayed connected while Android happened to keep the process around.

Android gives apps no way to hide, demote or redirect another app's notification. A notification
listener can't dismiss a foreground service's notification either. The only switch is the user's:
Spotify's notification channel in system settings.

## Decision

- Remastered posts its own media session and `MediaStyle` notification
  (`media/NowPlayingService`) mirroring App Remote's player state: art, title, artist, seek,
  previous, play/pause, next and like. Every control goes through `Player` (App Remote), and
  tapping the notification opens Now Playing.
- The service is a `mediaPlayback` foreground service while Spotify plays, which keeps the App
  Remote connection alive with no window open. When playback pauses, it leaves the foreground
  and the notification can be dismissed, which stops the service.
- The user turns off Spotify's playback notification channel themselves (Settings → Playback
  links to it). On by default, switchable in Settings.
- Platform `android.media.session` APIs: no new dependency.

## Consequences

- Hardware media buttons (headsets, Bluetooth) still go to Spotify. Android routes them to the
  app that's actually playing audio, and Spotify handles them the same way.
- With Spotify's channel left on, two media cards show. Both are local and playing, so SystemUI
  puts the one it last loaded first, and it skips a post that matches the card it already has.
  Spotify's card changes after ours on a skip, so Remastered reposts 1 s and 4 s after each
  change, flipping the tap intent's request code so SystemUI sees a change. Verified on the
  phone: ours stays first across skips. A change Spotify posts later than 4 s still wins until
  the next one.
- Android won't let a backgrounded app start a foreground service. If playback starts elsewhere
  while Remastered is closed (another device, a headset), the notification waits until
  Remastered next opens.
- Still to verify on the phone: Spotify keeps playing reliably with its own notification channel
  off on Android 17.
