package app.remastered.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.remastered.AppContainer
import app.remastered.SpotifyServices
import app.remastered.ui.components.fadeUp
import app.remastered.ui.history.HistoryArtistScreen
import app.remastered.ui.history.HistoryScreen
import app.remastered.ui.history.HistoryTrackScreen
import app.remastered.ui.lyrics.LyricsPreviewScreen
import app.remastered.ui.lyrics.LyricsScreen
import app.remastered.ui.player.AddToPlaylistSheet
import app.remastered.ui.player.BottomBar
import app.remastered.ui.player.DevicesSheet
import app.remastered.ui.player.MiniPlayer
import app.remastered.ui.player.NowPlaying
import app.remastered.ui.player.QueueScreen
import app.remastered.ui.player.TrackMenuSheet
import app.remastered.ui.screens.AlbumScreen
import app.remastered.ui.screens.ArtistScreen
import app.remastered.ui.screens.HomeScreen
import app.remastered.ui.screens.LibraryScreen
import app.remastered.ui.screens.LikedSongsScreen
import app.remastered.ui.screens.PlaylistScreen
import app.remastered.ui.screens.SearchScreen
import app.remastered.ui.screens.SettingsScreen
import app.remastered.ui.stats.StatsScreen
import app.remastered.ui.theme.Palette
import app.remastered.ui.theme.Type
import app.remastered.ui.wrapped.WrappedScreen
import app.remastered.ui.wrapped.WrappedPreview
import app.remastered.ui.wrapped.WrappedStory
import app.remastered.ui.wrapped.WrappedStoryScreen

/**
 * The whole UI for one [AppContainer]: sign-in if needed, then tabs, player and sheets.
 * [lyricsPreview] (debug builds only, see MainActivity) shows that track's lyrics preview first,
 * signed in or not; [wrappedPreview] likewise opens a Wrapped story.
 */
@Composable
fun RemasteredRoot(container: AppContainer, lyricsPreview: MutableState<String?>? = null, wrappedPreview: MutableState<WrappedPreview?>? = null) {
    val context = LocalContext.current
    val spotify = container.spotify
    lyricsPreview?.value?.let { uri ->
        BackHandler { lyricsPreview.value = null }
        LyricsPreviewScreen(container, uri) { lyricsPreview.value = null }
        return
    }
    wrappedPreview?.value?.let { preview ->
        BackHandler { wrappedPreview.value = null }
        WrappedStory(container, preview.from, preview.to, onClose = { wrappedPreview.value = null },
            message = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }, startFrame = preview.frame, dump = preview.dump)
        return
    }
    if (!SpotifyServices.isConfigured) {
        NotConfigured()
        return
    }
    if (!spotify.signedIn) {
        SignInScreen(spotify)
        return
    }
    val app = remember(container) { AppState(container, context.applicationContext) }
    LaunchedEffect(app) { app.start() }

    BackHandler(enabled = app.canGoBack) { app.back() }

    Box(Modifier.fillMaxSize().background(Palette.base)) {
        Crossfade(app.nav.current to app.nav.tab, animationSpec = tween(180), label = "screen") { (route, _) ->
            Box(Modifier.fillMaxSize()) {
                when (route) {
                    Route.Home -> HomeScreen(app)
                    Route.Search -> SearchScreen(app)
                    Route.Library -> LibraryScreen(app)
                    Route.LikedSongs -> LikedSongsScreen(app)
                    is Route.Playlist -> PlaylistScreen(app, route.id)
                    is Route.Album -> AlbumScreen(app, route.id)
                    is Route.Artist -> ArtistScreen(app, route.id)
                    Route.Settings -> SettingsScreen(app)
                    Route.History -> HistoryScreen(app)
                    is Route.HistoryDay -> HistoryScreen(app, route.day)
                    is Route.HistoryArtist -> HistoryArtistScreen(app, route.id, route.name)
                    is Route.HistoryTrack -> HistoryTrackScreen(app, route.id)
                    Route.Stats -> StatsScreen(app)
                    Route.Wrapped -> WrappedScreen(app)
                    is Route.WrappedStory -> WrappedStoryScreen(app, route.from, route.to)
                }
            }
        }
        if (app.nav.current !is Route.WrappedStory) {
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fadeUp().padding(top = 28.dp).navigationBarsPadding()) {
                MiniPlayer(app)
                BottomBar(app)
            }
        }
        AnimatedVisibility(app.nowPlayingOpen, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
            NowPlaying(app)
        }
        AnimatedVisibility(app.queueOpen, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
            QueueScreen(app)
        }
        AnimatedVisibility(app.lyricsOpen, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
            LyricsScreen(app)
        }
        // Keep the last uri while the preview animates out.
        val preview = app.lyricsPreview
        val shownPreview = remember { mutableStateOf<String?>(null) }.apply { if (preview != null) value = preview }
        AnimatedVisibility(preview != null, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
            shownPreview.value?.let { LyricsPreviewScreen(container, it) { app.lyricsPreview = null } }
        }
        SnackbarHost(app.snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 140.dp)) {
            Snackbar(it, containerColor = Palette.chipOn, contentColor = Palette.onChipOn)
        }
    }

    app.menu?.let { TrackMenuSheet(app, it) }
    if (app.addToPlaylist != null) AddToPlaylistSheet(app)
    if (app.devicesOpen) DevicesSheet(app)
}

/** A build without `spotify.clientId` in client/local.properties can't sign in or play. */
@Composable
private fun NotConfigured() {
    Column(Modifier.fillMaxSize().background(Palette.base).statusBarsPadding().navigationBarsPadding().padding(28.dp),
        verticalArrangement = Arrangement.Center) {
        Text("Remastered", style = Type.display, color = Palette.text)
        Text("This build has no Spotify client id. Add spotify.clientId and spotify.redirectUri to client/local.properties " +
            "(from developer.spotify.com/dashboard) and rebuild.",
            style = Type.bodyRegular, color = Palette.sub, modifier = Modifier.padding(top = 12.dp))
    }
}
