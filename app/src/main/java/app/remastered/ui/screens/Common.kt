package app.remastered.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import app.remastered.core.model.Album
import app.remastered.core.model.Page
import app.remastered.core.model.Track
import app.remastered.core.model.best
import app.remastered.ui.AppState
import app.remastered.ui.Route
import app.remastered.ui.components.Artwork
import app.remastered.ui.components.Card
import app.remastered.ui.components.IconButton
import app.remastered.ui.components.PlayButton
import app.remastered.ui.components.SectionHeader
import app.remastered.ui.components.TitleText
import app.remastered.ui.components.rememberArtworkTint
import app.remastered.ui.theme.Icons
import app.remastered.ui.theme.Palette
import app.remastered.ui.theme.Type

/** True while [contextUri] is what's playing (and not paused). */
@Composable
fun AppState.isPlaying(contextUri: String?): Boolean {
    val s by c.player.state.collectAsState()
    return contextUri != null && s?.context?.uri == contextUri && s?.isPaused == false
}

/** The floating back / more buttons over a page header. */
@Composable
fun TopBar(app: AppState, onMore: (() -> Unit)? = null, title: String? = null, solid: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().then(if (solid) Modifier.background(Palette.base) else Modifier)
            .statusBarsPadding().height(52.dp).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val glass = Modifier.clip(CircleShape).background(Color(0x59141724))
        IconButton(Icons.Back, "Back", { app.back() }, glass, tint = Palette.text)
        if (title != null && solid) TitleText(title, Type.body, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
        else Spacer(Modifier.weight(1f))
        if (onMore != null) IconButton(Icons.More, "More", onMore, glass, tint = Palette.text, iconSize = 20.dp)
    }
}

/**
 * The playlist / album header from the mockups: big cover over an artwork-tinted backdrop,
 * title, details, then a row of actions ending in shuffle + play.
 */
@Composable
fun CollectionHeader(
    imageUrl: String?,
    title: String,
    meta: String,
    playing: Boolean,
    onPlay: () -> Unit,
    shuffle: Boolean,
    onShuffle: () -> Unit,
    description: String? = null,
    ownerName: String? = null,
    ownerImage: String? = null,
    onOwner: (() -> Unit)? = null,
    cover: (@Composable () -> Unit)? = null,
    tintOverride: Color? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val tint = tintOverride ?: rememberArtworkTint(imageUrl)
    val top by animateColorAsState(lerp(tint, Palette.base, .45f), tween(600), label = "hdr")
    Column(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(0f to top, .75f to lerp(top, Palette.base, .8f), 1f to Palette.base))
            .statusBarsPadding().padding(top = 56.dp).padding(horizontal = 20.dp),
    ) {
        Box(Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).size(232.dp).shadow(28.dp, RoundedCornerShape(16.dp))) {
            cover?.invoke() ?: Artwork(imageUrl, Modifier.size(232.dp), RoundedCornerShape(16.dp))
        }
        Text(title, style = Type.display, color = Palette.text, modifier = Modifier.padding(top = 22.dp))
        if (!description.isNullOrBlank()) Text(description, style = Type.bodyRegular.copy(fontWeight = Type.caption.fontWeight), color = Color(0xFFD4D8E6), modifier = Modifier.padding(top = 8.dp), maxLines = 3)
        if (ownerName != null) Row(
            Modifier.padding(top = 12.dp).clip(CircleShape).clickable(enabled = onOwner != null) { onOwner?.invoke() }.padding(end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(ownerImage, Modifier.size(24.dp), CircleShape)
            Text(ownerName, style = Type.label.copy(fontWeight = Type.body.fontWeight), color = Palette.text, modifier = Modifier.padding(start = 8.dp))
        }
        Text(meta, style = Type.caption, color = Palette.sub, modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).padding(start = 0.dp), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.CenterVertically) { actions() }
            IconButton(Icons.Shuffle, if (shuffle) "Shuffle on" else "Shuffle off", onShuffle,
                tint = if (shuffle) Palette.accent else Palette.text, active = shuffle, iconSize = 24.dp)
            Spacer(Modifier.width(10.dp))
            PlayButton(playing, onPlay)
        }
    }
}

/** "More by <artist>" / discography carousel of albums. */
fun LazyListScope.albumCarousel(app: AppState, title: String, albums: List<Album>, action: String? = null, onAction: (() -> Unit)? = null) {
    if (albums.isEmpty()) return
    item(key = "carousel-$title") {
        Column(Modifier.padding(top = 30.dp)) {
            SectionHeader(title, action = action, onAction = onAction, small = true)
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.padding(top = 14.dp)) {
                items(albums.size, key = { albums[it].id }) { i ->
                    val a = albums[i]
                    Card(a.name, "${a.type.label()}  ·  ${a.year}", a.images.best()?.url, { app.open(Route.Album(a.id)) })
                }
            }
        }
    }
}

fun songs(n: Int) = if (n == 1) "1 song" else "%,d songs".format(n)

fun durationText(totalMs: Long): String {
    val min = totalMs / 60_000
    return if (min >= 60) "${min / 60} hr ${min % 60} min" else "$min min"
}

/** Reads pages from [fetch] until there are no more or [max] items are loaded. */
suspend fun <T> allPages(max: Int = 2_000, fetch: suspend (offset: Int) -> Page<T>): List<T> {
    val all = mutableListOf<T>()
    while (all.size < max) {
        val page = fetch(all.size)
        all += page.items
        if (!page.hasMore || page.items.isEmpty()) break
    }
    return all
}

/** Whether the user has saved [uri] (album, or followed playlist/artist); null while unknown. */
@Composable
fun AppState.rememberSaved(uri: String): MutableState<Boolean?> {
    val saved = remember(uri) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(uri) {
        saved.value = runCatching { c.catalog.libraryContains(listOf(uri)).firstOrNull() }.getOrNull()
    }
    return saved
}

/** The shuffle toggle on page headers mirrors the player's shuffle. */
@Composable
fun AppState.shuffleOn(): Boolean {
    val s by c.player.state.collectAsState()
    return s?.shuffle == true
}

/** A compact text field with a search icon: "Find in playlist", Library's filter. */
@Composable
fun FilterField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    Row(
        modifier.height(42.dp).clip(RoundedCornerShape(12.dp)).background(Palette.surface).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Search, null, Modifier.size(18.dp), tint = Palette.sub)
        Box(Modifier.weight(1f).padding(start = 10.dp)) {
            if (value.isEmpty()) Text(placeholder, style = Type.label.copy(fontWeight = Type.caption.fontWeight), color = Palette.sub)
            BasicTextField(value, onChange, singleLine = true, textStyle = Type.label.copy(color = Palette.text),
                cursorBrush = SolidColor(Palette.accent), modifier = Modifier.fillMaxWidth())
        }
        if (value.isNotEmpty()) IconButton(Icons.Close, "Clear", { onChange("") }, size = 28.dp, iconSize = 16.dp)
    }
}

/** True when [query] (lowercased) matches the track's name, artists or album. */
fun Track.matches(query: String): Boolean = query.isBlank() || listOfNotNull(name, artistNames, album?.name).any { it.lowercase().contains(query.trim().lowercase()) }

/** The header play button: pauses [uri] if it's playing, resumes it if paused, else starts it. */
fun AppState.togglePlay(uri: String) {
    val s = c.player.state.value
    when {
        s?.context?.uri != uri -> play(uri)
        s.isPaused -> c.player.resume()
        else -> c.player.pause()
    }
}
