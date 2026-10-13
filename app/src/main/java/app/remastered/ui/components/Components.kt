package app.remastered.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.remastered.core.model.Track
import app.remastered.core.model.best
import app.remastered.ui.theme.Icons
import app.remastered.ui.theme.Palette
import app.remastered.ui.theme.Type

@Composable
fun IconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Palette.sub,
    size: Dp = 40.dp,
    iconSize: Dp = 22.dp,
    active: Boolean = false,
    enabled: Boolean = true,
) {
    Box(
        modifier.size(size).clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, Modifier.size(iconSize), tint = if (enabled) tint else tint.copy(alpha = .35f))
        if (active) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp).size(4.dp).background(tint, CircleShape))
    }
}

/** The theme's rounded-square accent play button. */
@Composable
fun PlayButton(playing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 56.dp) {
    Box(
        modifier.size(size)
            .shadow(14.dp, RoundedCornerShape(size * .32f), ambientColor = Palette.accent, spotColor = Palette.accent)
            .clip(RoundedCornerShape(size * .32f)).background(Palette.accent)
            .clickable(role = Role.Button, onClickLabel = if (playing) "Pause" else "Play", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (playing) Icons.Pause else Icons.Play, if (playing) "Pause" else "Play",
            Modifier.size(size * .43f).padding(start = if (playing) 0.dp else 2.dp), tint = Color.White)
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(
        modifier.height(34.dp).clip(CircleShape)
            .background(if (selected) Palette.chipOn else Palette.surface)
            .then(if (selected) Modifier else Modifier.border(1.dp, Palette.line, CircleShape))
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = if (text.isEmpty()) 9.dp else 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val color = if (selected) Palette.onChipOn else Palette.text
        icon?.let { Icon(it, null, Modifier.size(16.dp), tint = color) }
        if (text.isNotEmpty()) Text(text, style = Type.label.copy(fontSize = Type.label.fontSize * 1.04f), color = color, maxLines = 1)
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null, small: Boolean = false) {
    Row(modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, style = if (small) Type.sectionSmall else Type.section, color = Palette.text, modifier = Modifier.weight(1f))
        if (action != null) Text(action, style = Type.label, color = Palette.sub,
            modifier = Modifier.clip(CircleShape).clickable(enabled = onAction != null) { onAction?.invoke() }.padding(4.dp))
    }
}

@Composable
fun TitleText(text: String, style: TextStyle = Type.body, color: Color = Palette.text, modifier: Modifier = Modifier, lines: Int = 1) =
    Text(text, style = style, color = color, maxLines = lines, overflow = TextOverflow.Ellipsis, modifier = modifier)

@Composable
fun SubText(text: String, modifier: Modifier = Modifier, color: Color = Palette.sub) =
    Text(text, style = Type.caption, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)

/** "Playlist · OhItsTom": joins non-empty parts with the mockups' middle dot. */
fun dotted(vararg parts: String?) = parts.filterNot { it.isNullOrBlank() }.joinToString("  ·  ")

/** Three animated bars for the row that's playing. */
@Composable
fun EqBars(modifier: Modifier = Modifier, color: Color = Palette.accent, animate: Boolean = true) {
    val t = rememberInfiniteTransition(label = "eq")
    val phases = listOf(0, 180, 90).map { delay ->
        if (animate) t.animateFloat(.35f, 1f, infiniteRepeatable(tween(420, delayMillis = delay), RepeatMode.Reverse), label = "bar").value else .7f
    }
    Row(modifier.size(14.dp, 12.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        phases.forEach { h -> Box(Modifier.width(3.dp).fillMaxHeight(h).clip(RoundedCornerShape(1.dp)).background(color)) }
    }
}

/** The Liked Songs tile: accent gradient with a heart. */
@Composable
fun LikedArt(modifier: Modifier = Modifier, shape: RoundedCornerShape = RoundedCornerShape(0.dp)) {
    Box(modifier.clip(shape).background(Brush.linearGradient(listOf(Color(0xFF5865C9), Palette.accent, Color(0xFFB9C6FF)))),
        contentAlignment = Alignment.Center) {
        Icon(Icons.HeartFilled, null, Modifier.fillMaxSize(.42f), tint = Color.White)
    }
}

@Composable
fun ExplicitBadge() {
    Box(Modifier.padding(end = 5.dp).size(15.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFF8E94AA)), contentAlignment = Alignment.Center) {
        Text("E", style = Type.tiny.copy(letterSpacing = Type.tiny.letterSpacing * 0), color = Palette.base)
    }
}

/**
 * One song in a list. [number] shows a track number instead of artwork (album pages).
 * Tap plays; long-press or the ⋯ button opens the song menu.
 */
@Composable
fun TrackRow(
    track: Track,
    onClick: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    playing: Boolean = false,
    liked: Boolean = false,
    number: Int? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().height(64.dp)
            .combinedClickable(onClick = onClick, onLongClick = onMore)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (number != null) {
            Box(Modifier.width(26.dp), contentAlignment = Alignment.Center) {
                if (playing) EqBars() else Text("$number", style = Type.bodyRegular, color = Palette.sub)
            }
        } else {
            Artwork(track.album?.images?.best(120)?.url, Modifier.size(48.dp), RoundedCornerShape(9.dp))
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (playing && number == null) { EqBars(); Spacer(Modifier.width(6.dp)) }
                TitleText(track.name, color = if (playing) Palette.accent else Palette.text)
            }
            Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                if (liked) Icon(Icons.HeartFilled, "Liked", Modifier.padding(end = 5.dp).size(13.dp), tint = Palette.accent)
                if (track.explicit) ExplicitBadge()
                SubText(track.artistNames)
            }
        }
        if (trailing != null) trailing() else IconButton(Icons.More, "More options for ${track.name}", onMore, tint = Palette.dim, iconSize = 20.dp)
    }
}

/** A row for a playlist, album or artist in lists (Library, search). */
@Composable
fun CollectionRow(
    title: String,
    subtitle: String,
    art: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    playing: Boolean = false,
    leadingSubIcon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().height(72.dp).clickable(onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        art()
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (playing) { EqBars(); Spacer(Modifier.width(6.dp)) }
                TitleText(title, color = if (playing) Palette.accent else Palette.text)
            }
            Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                leadingSubIcon?.let { Icon(it, null, Modifier.padding(end = 5.dp).size(14.dp), tint = Palette.accent) }
                SubText(subtitle)
            }
        }
        trailing?.invoke()
    }
}

/** A square (or round) card in a horizontal carousel. */
@Composable
fun Card(
    title: String,
    subtitle: String?,
    imageUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 148.dp,
    round: Boolean = false,
    centered: Boolean = false,
) {
    Column(modifier.width(size).clickable(onClick = onClick),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start) {
        val shape = if (round) CircleShape else RoundedCornerShape(14.dp)
        Artwork(imageUrl, Modifier.size(size).shadow(10.dp, shape), shape)
        TitleText(title, Type.body.copy(fontSize = Type.body.fontSize * .94f), modifier = Modifier.padding(top = 10.dp))
        subtitle?.let { SubText(it, Modifier.padding(top = 3.dp)) }
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Palette.accent, strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
    }
}

@Composable
fun ErrorMessage(message: String, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, style = Type.bodyRegular, color = Palette.sub)
        if (onRetry != null) Chip("Try again", selected = true, onClick = onRetry, modifier = Modifier.padding(top = 16.dp))
    }
}

@Composable
fun EmptyMessage(title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = Type.sectionSmall, color = Palette.text)
        Text(body, style = Type.caption, color = Palette.sub, modifier = Modifier.padding(top = 8.dp))
    }
}

/** A thin rule-free gradient fade used behind floating bars. */
fun Modifier.fadeUp(color: Color = Palette.base) = drawBehind {
    drawRect(Brush.verticalGradient(0f to color.copy(alpha = 0f), .38f to color.copy(alpha = .92f), .6f to color))
}

val ScreenPadding = PaddingValues(bottom = 170.dp)
