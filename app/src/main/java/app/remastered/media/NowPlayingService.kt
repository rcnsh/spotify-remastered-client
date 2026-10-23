package app.remastered.media

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState as SessionState
import android.os.SystemClock
import android.text.format.DateFormat
import android.util.Log
import app.remastered.MainActivity
import app.remastered.R
import app.remastered.RemasteredApp
import app.remastered.core.Connection
import app.remastered.core.NowPlaying
import app.remastered.core.PlaybackState
import app.remastered.core.SleepTimer
import app.remastered.spotify.remote.AppRemotePlayer
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Remastered's own media notification and session (ADR 0007): lock screen, Quick Settings and
 * the shade show what Spotify is playing, with controls that go through App Remote, and a tap
 * opens Remastered instead of Spotify.
 *
 * While playing it runs in the foreground, which keeps Remastered's process (and with it the
 * App Remote connection) alive with no window open. Paused, it leaves the foreground and the
 * notification can be swiped away, which stops it. [NowPlayingNotification] starts it.
 */
// NotificationPermission: a MediaStyle notification tied to a media session, as this one is, is
// exempt from POST_NOTIFICATIONS on Android 13+, so the app doesn't ask for it.
@SuppressLint("NotificationPermission")
class NowPlayingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var session: MediaSession
    private var player: AppRemotePlayer? = null
    private var sleepTimer: SleepTimer? = null
    private var foreground = false
    private var shownUri: String? = null
    /** Artwork for the current track's image url, once loaded. */
    private var art: Pair<String, Bitmap?>? = null
    private var artJob: Job? = null
    private var reconnect: Job? = null
    private var repost: Job? = null
    /** Request code of the notification's tap intent, flipped by each repost (see [repostLater]). */
    private var clickVariant = 0
    /** A button pressed before App Remote had reported anything (a fresh process): run on the first state. */
    private var pending: (() -> Unit)? = null

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Now playing", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Playback controls for what Spotify is playing"
            setShowBadge(false)
        })
        session = MediaSession(this, "Remastered").apply {
            setSessionActivity(openApp())
            setCallback(callback)
            isActive = true
        }
        val container = (application as RemasteredApp).container
        player = container?.spotify?.player
        sleepTimer = container?.sleepTimer
        // Started with startForegroundService: it has to go foreground now, whatever the state.
        goForeground(build(player?.state?.value))
        val p = player ?: return stopSelf()
        scope.launch { p.state.collect(::update) }
        if (p.state.value == null) {
            // A fresh process (the notification outlived it): connect, and give up if that fails.
            p.connect()
            scope.launch { delay(CONNECT_TIMEOUT_MS); if (p.state.value?.track == null) stopSelf() }
        }
        sleepTimer?.let { timer -> runSleepTimer(p, timer) }
        scope.launch {
            p.connection.collect { c ->
                reconnect?.cancel()
                // App Remote dropped (Spotify restarted, say): reconnect, so the controls keep working.
                if (c == Connection.Idle) reconnect = launch { delay(RECONNECT_DELAY_MS); p.connect() }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val p = player
        val run: (() -> Unit)? = when (intent?.action) {
            ACTION_PLAY_PAUSE -> { { p?.togglePlay() } }
            ACTION_NEXT -> { { p?.next() } }
            ACTION_PREVIOUS -> { { p?.previous() } }
            ACTION_LIKE -> ::toggleLike
            ACTION_DISMISS -> { if (p?.state?.value?.isPaused != false) stopSelf(); null }
            else -> null
        }
        if (run != null) { if (p?.state?.value == null) pending = run else run() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        session.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    /**
     * Runs the sleep timer here rather than in the activity, so it pauses with no window open.
     * Rechecks on every change of the timer or the playback state (a seek, a skip, a pause).
     */
    private fun runSleepTimer(p: AppRemotePlayer, timer: SleepTimer) {
        scope.launch {
            combine(timer.mode, p.state) { _, s -> s }.collectLatest { s ->
                while (true) {
                    when (val step = timer.step(s)) {
                        SleepTimer.Step.Idle -> break
                        SleepTimer.Step.Pause -> { p.pause(); break }
                        is SleepTimer.Step.Wait -> delay(step.ms)
                    }
                }
            }
        }
        // The notification says when it pauses.
        scope.launch { timer.mode.drop(1).collect { update(p.state.value) } }
    }

    /** "Sleep timer · 23:15" or "Sleep timer · end of song", or null while it's off. */
    private fun sleepText(): String? = when (val m = sleepTimer?.mode?.value) {
        null -> null
        SleepTimer.Mode.EndOfTrack -> "Sleep timer · end of ${if (player?.state?.value?.track?.isEpisode == true) "episode" else "song"}"
        is SleepTimer.Mode.After -> {
            val at = System.currentTimeMillis() + (m.atMs - SystemClock.elapsedRealtime())
            "Sleep timer · ${DateFormat.getTimeFormat(this).format(Date(at))}"
        }
    }

    private fun toggleLike() {
        val track = player?.state?.value?.track?.takeIf { !it.isEpisode } ?: return
        player?.setLiked(track.uri, !track.liked)
    }

    private val callback = object : MediaSession.Callback() {
        override fun onPlay() { player?.resume() }
        override fun onPause() { player?.pause() }
        override fun onStop() { player?.pause() }
        override fun onSkipToNext() { player?.next() }
        override fun onSkipToPrevious() { player?.previous() }
        override fun onSeekTo(pos: Long) { player?.seekTo(pos) }
        override fun onCustomAction(action: String, extras: android.os.Bundle?) { if (action == ACTION_LIKE) toggleLike() }
    }

    private fun update(state: PlaybackState?) {
        // Not connected yet: wait (onCreate gives up after a while).
        if (state == null) return
        pending?.let { pending = null; it() }
        val controls = MediaControls.of(state)
        val track = state.track
        if (controls == null || track == null) {
            // Nothing loaded in Spotify any more: nothing to show.
            stopSelf()
            return
        }
        session.setPlaybackState(SessionState.Builder()
            .setActions(controls.actions)
            .setState(if (controls.playing) SessionState.STATE_PLAYING else SessionState.STATE_PAUSED,
                controls.positionMs, controls.speed, controls.positionAtMs)
            .apply {
                if (controls.canLike) addCustomAction(SessionState.CustomAction.Builder(ACTION_LIKE,
                    if (controls.liked) "Remove from Liked Songs" else "Add to Liked Songs",
                    if (controls.liked) R.drawable.ic_media_heart_filled else R.drawable.ic_media_heart).build())
            }
            .build())
        if (track.uri != shownUri) {
            shownUri = track.uri
            loadArt(track)
        }
        session.setMetadata(metadata(track))
        val notification = build(state)
        if (controls.playing) goForeground(notification)
        else {
            if (foreground) stopForeground(STOP_FOREGROUND_DETACH)
            foreground = false
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        }
        repostLater()
    }

    /**
     * Posts the notification again once a change has settled. With Spotify's own notification
     * still on, both cards are local and playing, so SystemUI puts the one it last loaded first.
     * Spotify's card changes after ours (App Remote hears of a track before Spotify's session
     * shows it), so without this it ends up ahead. SystemUI skips a post that matches the card
     * it has, so each repost flips the tap intent's request code: a change it can see, with the
     * same destination.
     */
    private fun repostLater() {
        repost?.cancel()
        repost = scope.launch {
            for (wait in REPOST_AFTER_MS) {
                delay(wait)
                val state = player?.state?.value ?: return@launch
                if (state.track == null) return@launch
                clickVariant = 1 - clickVariant
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(state))
            }
        }
    }

    private fun metadata(track: NowPlaying) = MediaMetadata.Builder()
        .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, track.uri)
        .putString(MediaMetadata.METADATA_KEY_TITLE, track.name)
        .putString(MediaMetadata.METADATA_KEY_ARTIST, track.artistNames)
        .putString(MediaMetadata.METADATA_KEY_ALBUM, track.albumName)
        .putLong(MediaMetadata.METADATA_KEY_DURATION, track.durationMs)
        .apply { currentArt(track)?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }
        .build()

    private fun currentArt(track: NowPlaying) = art?.takeIf { it.first == track.imageUrl }?.second

    /** Loads the cover off the main thread, then shows it (if the track hasn't changed meanwhile). */
    private fun loadArt(track: NowPlaying) {
        artJob?.cancel()
        val url = track.imageUrl ?: return
        if (art?.first == url) return
        artJob = scope.launch {
            art = url to loadArtwork(this@NowPlayingService, player, url, ART_PX)
            update(player?.state?.value)
        }
    }

    private fun build(state: PlaybackState?): Notification {
        val track = state?.track
        val controls = MediaControls.of(state)
        val playing = controls?.playing == true
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_note)
            .setContentTitle(track?.name ?: "Remastered")
            .setContentText(track?.artistNames)
            .setLargeIcon(track?.let(::currentArt))
            .setContentIntent(openApp(clickVariant))
            .setDeleteIntent(command(ACTION_DISMISS))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setOngoing(playing)
            .setSubText(sleepText())
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
        // Before Android 13 these buttons are what the notification shows; since, the system
        // builds the controls from the session (actions + the like custom action) instead.
        if (controls != null) {
            builder.addAction(action(R.drawable.ic_media_previous, "Previous", ACTION_PREVIOUS))
            builder.addAction(if (playing) action(R.drawable.ic_media_pause, "Pause", ACTION_PLAY_PAUSE)
                else action(R.drawable.ic_media_play, "Play", ACTION_PLAY_PAUSE))
            builder.addAction(action(R.drawable.ic_media_next, "Next", ACTION_NEXT))
            if (controls.canLike) builder.addAction(if (controls.liked) action(R.drawable.ic_media_heart_filled, "Remove from Liked Songs", ACTION_LIKE)
                else action(R.drawable.ic_media_heart, "Add to Liked Songs", ACTION_LIKE))
        }
        builder.setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken)
            .apply { if (controls != null) setShowActionsInCompactView(0, 1, 2) })
        return builder.build()
    }

    private fun goForeground(notification: Notification) {
        try {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            foreground = true
        } catch (e: IllegalStateException) {
            // Android won't let a backgrounded app go foreground (playback resumed from a
            // headset or another device): keep the notification up without it.
            Log.w(TAG, "Can't go foreground: ${e.javaClass.simpleName}")
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        }
    }

    private fun action(icon: Int, title: String, action: String) =
        Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, icon), title, command(action)).build()

    private fun command(action: String): PendingIntent = PendingIntent.getService(
        this, action.hashCode(), Intent(this, NowPlayingService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun openApp(requestCode: Int = 0): PendingIntent = PendingIntent.getActivity(
        this, requestCode, Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_NOW_PLAYING)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val TAG = "NowPlayingService"
        private const val CHANNEL = "now_playing"
        private const val NOTIFICATION_ID = 1
        private const val ART_PX = 512
        private const val RECONNECT_DELAY_MS = 3_000L
        private const val CONNECT_TIMEOUT_MS = 20_000L
        /** Delays between reposts after a change: soon after Spotify's card updates, then once more if it was slow. */
        private val REPOST_AFTER_MS = longArrayOf(1_000L, 4_000L)
        private const val ACTION_PLAY_PAUSE = "app.remastered.media.PLAY_PAUSE"
        private const val ACTION_NEXT = "app.remastered.media.NEXT"
        private const val ACTION_PREVIOUS = "app.remastered.media.PREVIOUS"
        private const val ACTION_LIKE = "app.remastered.media.LIKE"
        private const val ACTION_DISMISS = "app.remastered.media.DISMISS"

        fun intent(context: Context) = Intent(context, NowPlayingService::class.java)
    }
}
