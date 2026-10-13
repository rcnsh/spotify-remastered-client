package app.remastered.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.remastered.AppContainer
import app.remastered.core.model.Device
import app.remastered.core.model.DeviceType
import app.remastered.core.model.Playlist
import app.remastered.core.model.best
import app.remastered.core.model.canEdit
import app.remastered.core.warehouse.WarehouseFeature
import app.remastered.ui.AppState
import app.remastered.ui.Load
import app.remastered.ui.Route
import app.remastered.ui.TrackMenu
import app.remastered.ui.components.Artwork
import app.remastered.ui.components.EqBars
import app.remastered.ui.components.ErrorMessage
import app.remastered.ui.components.Loading
import app.remastered.ui.components.SubText
import app.remastered.ui.components.TitleText
import app.remastered.ui.history.HistoryIcons
import app.remastered.ui.history.warehouseSupports
import app.remastered.ui.load
import app.remastered.ui.theme.Icons
import app.remastered.ui.theme.Palette
import app.remastered.ui.theme.Type
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Sheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.surface,
        scrimColor = Color(0xA0090B12),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 16.dp).size(40.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = .25f))) },
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 16.dp), content = content)
    }
}

@Composable
private fun MenuItem(icon: ImageVector, label: String, accent: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(24.dp), tint = if (accent) Palette.accent else Palette.sub)
        Text(label, style = Type.body.copy(fontSize = 15.5.sp), color = Palette.text, modifier = Modifier.padding(start = 18.dp))
    }
}

@Composable
fun TrackMenuSheet(app: AppState, menu: TrackMenu) {
    val t = menu.track
    val liked = app.c.liked.uris.collectAsState().value.contains(t.uri) || app.c.player.state.value?.track?.let { it.uri == t.uri && it.liked } == true
    fun then(action: () -> Unit) { app.menu = null; action() }
    Sheet({ app.menu = null }) {
        Row(Modifier.padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(t.album?.images?.best(120)?.url, Modifier.size(56.dp), RoundedCornerShape(10.dp))
            Column(Modifier.padding(start = 14.dp)) {
                TitleText(t.name, Type.body.copy(fontSize = 16.5.sp))
                SubText(listOfNotNull(t.artistNames, t.album?.name).joinToString("  ·  "), Modifier.padding(top = 3.dp))
                if (app.c.settings.showUris.value.collectAsState().value) SubText(t.uri, Modifier.padding(top = 3.dp), color = Palette.dim)
            }
        }
        HorizontalDivider(color = Palette.line)
        MenuItem(if (liked) Icons.HeartFilled else Icons.Heart, if (liked) "Remove from Liked Songs" else "Add to Liked Songs", accent = true) {
            then { app.setLiked(t.uri, !liked, t) }
        }
        if (!t.isEpisode) MenuItem(Icons.AddPlaylist, "Add to playlist") { then { app.addToPlaylist = t } }
        MenuItem(Icons.AddQueue, "Add to queue") { then { app.queue(t) } }
        if (menu.playlist != null && menu.playlist.canEdit(app.me)) {
            MenuItem(Icons.Remove, "Remove from ${menu.playlist.name}") { then { app.removeFrom(menu.playlist, t) } }
        }
        if (!t.isEpisode && app.warehouseSupports(WarehouseFeature.TrackHistory)) {
            MenuItem(HistoryIcons.History, "Your plays") { then { app.open(Route.HistoryTrack(t.id)) } }
        }
        t.album?.let { album -> MenuItem(Icons.Disc, "Go to album") { then { app.open(Route.Album(album.id)) } } }
        t.artists.firstOrNull()?.let { artist -> MenuItem(Icons.User, "Go to artist") { then { app.open(Route.Artist(artist.id)) } } }
        MenuItem(Icons.Share, "Share") { then { app.share(t.uri, t.name) } }
    }
}

@Composable
fun AddToPlaylistSheet(app: AppState) {
    val track = app.addToPlaylist ?: return
    val playlists = load(app.playlistsVersion) { app.c.catalog.myPlaylists(0, 50).items.filter { it.canEdit(app.me) } }
    val scope = rememberCoroutineScope()
    Sheet({ app.addToPlaylist = null }) {
        Text("Add to playlist", style = Type.sectionSmall, color = Palette.text, modifier = Modifier.padding(bottom = 12.dp))
        MenuItem(Icons.Plus, "New playlist", accent = true) {
            scope.launch {
                app.addToPlaylist = null
                runCatching { app.c.catalog.createPlaylist(track.name) }.onSuccess { app.addTo(it, track) }
                    .onFailure { app.message(AppState.describe(it)) }
            }
        }
        when (val s = playlists.state) {
            Load.Loading -> Loading()
            is Load.Failed -> ErrorMessage(s.message, playlists.retry)
            is Load.Done -> LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(s.value, key = { it.id }) { p ->
                    PlaylistPickRow(p) { app.addToPlaylist = null; app.addTo(p, track) }
                }
            }
        }
    }
}

@Composable
private fun PlaylistPickRow(p: Playlist, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Artwork(p.images.best(120)?.url, Modifier.size(48.dp), RoundedCornerShape(9.dp))
        Column(Modifier.padding(start = 14.dp)) {
            TitleText(p.name)
            SubText("${p.trackCount} songs", Modifier.padding(top = 3.dp))
        }
    }
}

private fun DeviceType.icon() = when (this) {
    DeviceType.Smartphone, DeviceType.Tablet -> Icons.Phone
    DeviceType.Computer -> Icons.Laptop
    DeviceType.TV, DeviceType.CastVideo -> Icons.Tv
    else -> Icons.Speaker
}

private fun DeviceType.label() = when (this) {
    DeviceType.Smartphone -> "Phone"; DeviceType.Tablet -> "Tablet"; DeviceType.Computer -> "Computer"
    DeviceType.TV -> "TV"; DeviceType.CastVideo, DeviceType.CastAudio -> "Google Cast"
    DeviceType.Speaker -> "Spotify Connect speaker"; DeviceType.Other -> "Spotify Connect"
}

@Composable
fun DevicesSheet(app: AppState) {
    var version by remember { mutableIntStateOf(0) }
    val devices = load(version) { app.c.catalog.devices() }
    Sheet({ app.devicesOpen = false }) {
        Text("Connect to a device", style = Type.sectionSmall.copy(fontSize = 19.sp), color = Palette.text)
        when (val s = devices.state) {
            Load.Loading -> Loading()
            is Load.Failed -> ErrorMessage(s.message, devices.retry)
            is Load.Done -> {
                val active = s.value.firstOrNull { it.isActive }
                if (active != null) ActiveDevice(app, active)
                val others = s.value.filter { it != active }
                if (others.isNotEmpty()) {
                    Text("OTHER DEVICES", style = Type.tiny.copy(fontSize = 12.sp), color = Palette.dim, modifier = Modifier.padding(top = 22.dp, bottom = 4.dp))
                    others.forEach { d ->
                        Row(Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = d.id != null) { app.launch { app.c.catalog.transferPlayback(d.id!!); version++ } },
                            verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(Palette.selected), contentAlignment = Alignment.Center) {
                                Icon(d.type.icon(), null, Modifier.size(24.dp), tint = Palette.text)
                            }
                            Column(Modifier.padding(start = 14.dp)) { TitleText(d.name); SubText(d.type.label(), Modifier.padding(top = 3.dp)) }
                        }
                    }
                }
                if (active == null && others.isEmpty()) {
                    Text("No devices found. Open Spotify on a phone, computer or speaker to see it here.", style = Type.caption, color = Palette.sub, modifier = Modifier.padding(vertical = 20.dp))
                }
                Text("Devices appear when Spotify is open on them.", style = Type.caption, color = Palette.dim, modifier = Modifier.padding(top = 14.dp))
            }
        }
    }
}

@Composable
private fun ActiveDevice(app: AppState, d: Device) {
    var volume by remember(d.id) { mutableFloatStateOf((d.volumePercent ?: 0) / 100f) }
    Column(Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
        .background(Brush.linearGradient(listOf(Palette.accent.copy(alpha = .28f), Palette.accent.copy(alpha = .08f))))
        .border(1.dp, Palette.accent.copy(alpha = .35f), RoundedCornerShape(18.dp)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(Palette.accent), contentAlignment = Alignment.Center) {
                Icon(d.type.icon(), null, Modifier.size(24.dp), tint = Color.White)
            }
            Column(Modifier.padding(start = 14.dp)) {
                TitleText(if (d.type == DeviceType.Smartphone && !AppContainer.isEmulator) "This phone" else d.name)
                Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    EqBars(); Spacer(Modifier.width(6.dp)); SubText(d.name, color = Color(0xFFC3CCF5))
                }
            }
        }
        if (d.supportsVolume) Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.VolumeLow, null, Modifier.size(20.dp), tint = Palette.sub)
            Slider(volume, { volume = it }, Modifier.weight(1f).padding(horizontal = 10.dp),
                onValueChangeFinished = { app.launch { app.c.catalog.setVolume((volume * 100).toInt(), d.id) } },
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = .15f)))
            Icon(Icons.Volume, null, Modifier.size(20.dp), tint = Palette.sub)
        }
    }
}
