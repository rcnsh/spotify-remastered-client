package app.remastered.ui

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.remastered.SpotifyServices
import app.remastered.ui.components.EqBars
import app.remastered.ui.theme.Palette
import app.remastered.ui.theme.Type
import kotlinx.coroutines.launch

/** First run in Spotify mode: Web API sign-in (PKCE, through Spotify's auth screen). */
@Composable
fun SignInScreen(spotify: SpotifyServices) {
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        scope.launch {
            error = spotify.finishLogin(result.resultCode, result.data)
            busy = false
        }
    }

    // Content rises in once, after the mosaic is already moving.
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(700, delayMillis = 120)) }

    Box(Modifier.fillMaxSize().background(Palette.bg)) {
        ArtMosaic(Modifier.fillMaxWidth().fillMaxSize(.62f))
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
                .padding(horizontal = 24.dp).padding(bottom = 20.dp)
                .graphicsLayer { alpha = enter.value; translationY = (1 - enter.value) * 40.dp.toPx() },
        ) {
            BrandMark()
            Text("Your music,\nremastered.", style = Type.display.copy(fontSize = Type.display.fontSize * 1.25f,
                lineHeight = Type.display.lineHeight * 1.25f), color = Palette.text, modifier = Modifier.padding(top = 20.dp))
            Text("A cleaner, calmer way to play your Spotify library. Sign in to bring in your playlists, albums and Liked Songs.",
                style = Type.bodyRegular, color = Palette.sub, modifier = Modifier.padding(top = 12.dp))

            AnimatedVisibility(error != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Text(error.orEmpty(), style = Type.caption, color = Palette.heart,
                    modifier = Modifier.padding(top = 18.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(Palette.heart.copy(alpha = .12f)).padding(horizontal = 14.dp, vertical = 10.dp))
            }

            PrimaryButton("Continue with Spotify", busy = busy, modifier = Modifier.padding(top = 28.dp)) {
                if (activity != null) {
                    error = null
                    busy = true
                    launcher.launch(spotify.loginIntent(activity))
                }
            }
            Text("You sign in on Spotify's own page. Playback goes through the Spotify app on this phone.",
                style = Type.caption, color = Palette.dim, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp, start = 12.dp, end = 12.dp))
        }
    }
}

/** App mark: the accent rounded square from the play button, with the now-playing bars. */
@Composable
private fun BrandMark() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(40.dp).shadow(16.dp, RoundedCornerShape(13.dp), ambientColor = Palette.accent, spotColor = Palette.accent)
                .clip(RoundedCornerShape(13.dp))
                .background(Brush.linearGradient(listOf(Palette.accentLight, Palette.accent, Color(0xFF5865C9)))),
            contentAlignment = Alignment.Center,
        ) { EqBars(Modifier.size(18.dp, 16.dp), color = Color.White) }
        Text("Remastered", style = Type.sectionSmall, color = Palette.text, modifier = Modifier.padding(start = 12.dp))
    }
}

/**
 * A tilted wall of cover art drifting slowly, fading into the background at the bottom.
 * Columns move in opposite directions.
 */
@Composable
private fun ArtMosaic(modifier: Modifier) {
    val columns = (0 until 4).map { c -> (0 until 5).map { r -> Tiles[(c * 5 + r * 3) % Tiles.size] } }
    val drift by rememberInfiniteTransition(label = "mosaic")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(18_000, easing = LinearEasing), RepeatMode.Reverse), label = "drift")
    val base = Palette.bg
    BoxWithConstraints(
        modifier.clip(RoundedCornerShape(0.dp)).drawWithContent {
            drawContent()
            drawRect(Brush.radialGradient(listOf(Palette.accent.copy(alpha = .28f), Color.Transparent),
                Offset(size.width * .5f, size.height * .35f), size.width * .9f))
            drawRect(Brush.verticalGradient(0f to base.copy(alpha = .15f), .45f to base.copy(alpha = .35f), .88f to base, 1f to base))
        },
    ) {
        val tile = maxWidth * .3f
        Row(
            Modifier.align(Alignment.Center).requiredWidth(maxWidth * 1.35f)
                .graphicsLayer { rotationZ = -12f; translationY = -tile.toPx() * .4f },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            columns.forEachIndexed { i, keys ->
                val dir = if (i % 2 == 0) 1f else -1f
                Column(
                    Modifier.weight(1f).graphicsLayer { translationY = (drift - .5f) * dir * tile.toPx() * .9f + i * tile.toPx() * .35f },
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    keys.forEachIndexed { r, tile ->
                        CoverTile(tile, ring = (i + r) % 3 == 0, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(14.dp)))
                    }
                }
            }
        }
    }
}

@Composable
private fun PrimaryButton(text: String, busy: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.fillMaxWidth().height(54.dp)
            .shadow(18.dp, CircleShape, ambientColor = Palette.accent, spotColor = Palette.accent)
            .clip(CircleShape).background(Palette.accent)
            .clickable(enabled = !busy, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) CircularProgressIndicator(color = Color.White, strokeWidth = 2.5.dp, modifier = Modifier.size(22.dp))
        else Text(text, style = Type.body.copy(fontSize = Type.body.fontSize * 1.07f), color = Color.White)
    }
}

/** Cover-art stand-ins: two-tone gradient, a soft highlight, and a sun or ring motif. */
private class Tile(val a: Color, val b: Color, val c: Color)

private val Tiles = listOf(
    Tile(Color(0xFFF3B98A), Color(0xFFD9705E), Color(0xFFFDE7CF)),
    Tile(Color(0xFF59709F), Color(0xFF232C47), Color(0xFFC8A6DD)),
    Tile(Color(0xFF5A3BC2), Color(0xFF141238), Color(0xFFEA6FE2)),
    Tile(Color(0xFF8FCFBF), Color(0xFF2F6F68), Color(0xFFEAF7EF)),
    Tile(Color(0xFFF39AB3), Color(0xFF5F3A8C), Color(0xFFFFE3EC)),
    Tile(Color(0xFF7C1626), Color(0xFF200B10), Color(0xFFE8545A)),
    Tile(Color(0xFF9FB3D9), Color(0xFF283A64), Color(0xFFE6EEFF)),
    Tile(Color(0xFF2C3F7A), Color(0xFF0E1631), Color(0xFFFFD38A)),
    Tile(Color(0xFF3D7A8F), Color(0xFF14283A), Color(0xFF9BE7D8)),
    Tile(Color(0xFFF7C9D8), Color(0xFFB56A8F), Color(0xFFFFF4F7)),
    Tile(Color(0xFF7A6EE8), Color(0xFF211B55), Color(0xFFF0D5FF)),
)

@Composable
private fun CoverTile(tile: Tile, ring: Boolean, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        drawRect(Brush.linearGradient(listOf(tile.a, tile.b), Offset.Zero, Offset(w, h)))
        drawRect(Brush.radialGradient(listOf(tile.c.copy(alpha = .7f), Color.Transparent), Offset(w * .18f, h * .12f), w * .62f))
        if (ring) for (n in 1..6) drawCircle(Color.White.copy(alpha = .14f), w * .14f * n, Offset(w * .3f, h * .72f), style = Stroke(w * .013f))
        else drawCircle(tile.c.copy(alpha = .85f), w * .2f, Offset(w * .68f, h * .4f))
    }
}
