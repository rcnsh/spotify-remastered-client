package app.remastered.media

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.widget.RemoteViews
import app.remastered.MainActivity
import app.remastered.R
import app.remastered.RemasteredApp
import app.remastered.core.PlaybackState
import app.remastered.spotify.remote.AppRemotePlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What the home screen widget shows for a [PlaybackState]: plain values, so the mapping is
 * testable without Android. [Idle] when App Remote hasn't reported anything (Spotify not
 * connected, a fresh process) or nothing is loaded.
 */
sealed interface WidgetModel {
    data object Idle : WidgetModel
    data class Track(
        val title: String,
        val artist: String,
        val imageUrl: String?,
        val playing: Boolean,
        val canSkipNext: Boolean,
        val canSkipPrevious: Boolean,
        /** Songs can be liked; podcast episodes can't. */
        val canLike: Boolean,
        val liked: Boolean,
    ) : WidgetModel

    companion object {
        fun of(state: PlaybackState?): WidgetModel {
            val track = state?.track ?: return Idle
            return Track(track.name, track.artistNames, track.imageUrl, !state.isPaused, state.canSkipNext, state.canSkipPrevious,
                canLike = !track.isEpisode, liked = track.liked)
        }
    }
}

/**
 * The home screen widget: what's playing, with previous, play/pause, next and like. Android
 * calls this when one is placed or resized; [NowPlayingWidgets] keeps them current after that.
 */
class NowPlayingWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val widgets = (context.applicationContext as RemasteredApp).container?.widgets
        if (widgets != null) widgets.refresh()
        else manager.updateAppWidget(ids, NowPlayingWidgets.views(context, WidgetModel.Idle, null))
    }
}

/**
 * The widget's buttons. Not exported (unlike [NowPlayingWidget], which the launcher has to
 * reach), so other apps can't press them. Like the notification's, every control goes through
 * App Remote; with nothing connected yet (a fresh process) it connects first.
 */
class NowPlayingWidgetCommands : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as RemasteredApp).container ?: return
        val player = container.spotify.player
        val run: () -> Unit = when (intent.action) {
            ACTION_PLAY_PAUSE -> player::togglePlay
            ACTION_NEXT -> player::next
            ACTION_PREVIOUS -> player::previous
            ACTION_LIKE -> { { player.state.value?.track?.takeIf { !it.isEpisode }?.let { player.setLiked(it.uri, !it.liked) } } }
            else -> return
        }
        if (player.state.value != null) return run()
        val pending = goAsync()
        container.scope.launch {
            try {
                player.connect()
                if (withTimeoutOrNull(CONNECT_TIMEOUT_MS) { player.state.filterNotNull().first() } != null) run()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /** Within the 10 s a receiver may take. */
        private const val CONNECT_TIMEOUT_MS = 8_000L
        const val ACTION_PLAY_PAUSE = "app.remastered.widget.PLAY_PAUSE"
        const val ACTION_NEXT = "app.remastered.widget.NEXT"
        const val ACTION_PREVIOUS = "app.remastered.widget.PREVIOUS"
        const val ACTION_LIKE = "app.remastered.widget.LIKE"
    }
}

/**
 * Keeps every placed [NowPlayingWidget] in step with App Remote's player state (the same state
 * the media notification shows), with the cover once it's loaded. Does nothing while none is placed.
 */
class NowPlayingWidgets(private val context: Context, private val player: AppRemotePlayer, private val scope: CoroutineScope) {
    private val manager = AppWidgetManager.getInstance(context)
    private val component = ComponentName(context, NowPlayingWidget::class.java)
    private var model: WidgetModel = WidgetModel.Idle
    /** The cover for an image url, once loaded. */
    private var art: Pair<String, Bitmap?>? = null
    private var artJob: Job? = null

    init {
        scope.launch {
            player.state.map(WidgetModel::of).distinctUntilChanged().collect { model = it; refresh() }
        }
    }

    /** Draws the current state on every placed widget (none placed: nothing to do). */
    fun refresh() {
        val ids = manager.getAppWidgetIds(component)
        if (ids.isEmpty()) return
        val m = model
        val url = (m as? WidgetModel.Track)?.imageUrl
        val cover = art?.takeIf { it.first == url }?.second
        manager.updateAppWidget(ids, views(context, m, cover))
        if (url != null && art?.first != url && artJob?.isActive != true) {
            artJob = scope.launch {
                art = url to loadArtwork(context, player, url, ART_PX)
                refresh()
            }
        }
    }

    companion object {
        private const val ART_PX = 300

        fun views(context: Context, model: WidgetModel, cover: Bitmap?) = RemoteViews(context.packageName, R.layout.widget_now_playing).apply {
            setOnClickPendingIntent(R.id.widget_root, openNowPlaying(context))
            setOnClickPendingIntent(R.id.widget_play, command(context, NowPlayingWidgetCommands.ACTION_PLAY_PAUSE))
            setOnClickPendingIntent(R.id.widget_previous, command(context, NowPlayingWidgetCommands.ACTION_PREVIOUS))
            setOnClickPendingIntent(R.id.widget_next, command(context, NowPlayingWidgetCommands.ACTION_NEXT))
            setOnClickPendingIntent(R.id.widget_like, command(context, NowPlayingWidgetCommands.ACTION_LIKE))
            when (model) {
                WidgetModel.Idle -> {
                    setTextViewText(R.id.widget_title, "Not playing")
                    setTextViewText(R.id.widget_artist, "Play something, or tap to open Remastered")
                    setViewVisibility(R.id.widget_art, View.GONE)
                    setImageViewResource(R.id.widget_play, R.drawable.ic_media_play)
                    setContentDescription(R.id.widget_play, "Play")
                    // Play stays: it connects and resumes what Spotify played last.
                    listOf(R.id.widget_previous, R.id.widget_next, R.id.widget_like).forEach { setViewVisibility(it, View.INVISIBLE) }
                }
                is WidgetModel.Track -> {
                    setTextViewText(R.id.widget_title, model.title)
                    setTextViewText(R.id.widget_artist, model.artist)
                    if (cover != null) setImageViewBitmap(R.id.widget_art, cover)
                    setViewVisibility(R.id.widget_art, if (cover != null) View.VISIBLE else View.GONE)
                    setImageViewResource(R.id.widget_play, if (model.playing) R.drawable.ic_media_pause else R.drawable.ic_media_play)
                    setContentDescription(R.id.widget_play, if (model.playing) "Pause" else "Play")
                    setViewVisibility(R.id.widget_previous, if (model.canSkipPrevious) View.VISIBLE else View.INVISIBLE)
                    setViewVisibility(R.id.widget_next, if (model.canSkipNext) View.VISIBLE else View.INVISIBLE)
                    setViewVisibility(R.id.widget_like, if (model.canLike) View.VISIBLE else View.INVISIBLE)
                    setImageViewResource(R.id.widget_like, if (model.liked) R.drawable.ic_widget_heart_filled else R.drawable.ic_media_heart)
                    setContentDescription(R.id.widget_like, if (model.liked) "Remove from Liked Songs" else "Add to Liked Songs")
                }
            }
        }

        private fun command(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
            context, action.hashCode(), Intent(context, NowPlayingWidgetCommands::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
        )

        private fun openNowPlaying(context: Context): PendingIntent = PendingIntent.getActivity(
            context, R.id.widget_root, Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_NOW_PLAYING)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
