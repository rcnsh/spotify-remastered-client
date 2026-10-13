package app.remastered.ui.wrapped

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.remastered.core.warehouse.WrappedArtist
import app.remastered.core.wrapped.Springs
import app.remastered.core.wrapped.WrappedFormat
import app.remastered.core.wrapped.fitText
import app.remastered.core.wrapped.interpolateClamped
import kotlin.math.min

// Web: remotion/slides/artists.tsx

/** "YOUR TOP ARTIST" zooming in line by line until it fills the frame. */
@Composable
fun SlideFrame.ArtistTease() {
    Box(Modifier.fillMaxSize().background(Pop.pink)) {
        Doodle(Doodles.burst, 780, 190, 220, 220, stroke = 9f, delay = 4)
        // Vertically centred like the web's flex column, then 60 up (its margin-top: -120).
        Column(Modifier.align(Alignment.CenterStart).padding(start = 70.dp).css(ty = -60f)) {
            listOf("Your", "top", "artist").forEachIndexed { i, line ->
                val zoom = interpolateClamped((frame - i * 5).toDouble(), 0.0, 12.0, 2.6, 1.0).toFloat()
                Poster(line, 940, 420, modifier = Modifier.css(scale = zoom, origin = LeftCenter, opacity = if (frame - i * 5 >= 0) 1f else 0f))
            }
        }
        Marker("was…", 96, rotate = -6f, modifier = Modifier.at(640, 1600), opacity = if (frame > 30) 1f else 0f)
    }
}

@Composable
fun SlideFrame.Artist() {
    val artist = data.topArtists[0]
    val image = assets.artistImage(artist.name, artist.imageUrl)
    val card = enter(0, Springs.bouncy)
    val name = enter(16, Springs.snappy)
    val stats = enter(34)
    val note = enter(60, Springs.bouncy)
    val jitter = jitter("artist", 1.4)
    Box(Modifier.fillMaxSize().background(Ink)) {
        Cutout(image, 700, border = 18, modifier = Modifier.at(190, 170).css(jitter.x, (1 - card) * -1200 + jitter.y, rotate = -4 + jitter.r))
        Tape(230, 190, -35f, seed = "artist-a")
        Tape(860, 840, -30f, seed = "artist-b")
        Stack(bottom = 120, gap = 28) {
            PosterLines(artist.name, 920, 2, 220, Pop.acid, Modifier.css(tx = (1 - name) * 1100))
            Mono(
                "${WrappedFormat.number(artist.minutes)} minutes\n${WrappedFormat.percent(artist.share)} of everything you played\non ${WrappedFormat.number(artist.days)} different days",
                44, Paper, Modifier.css(ty = (1 - stats) * 40, opacity = stats),
            )
            if (artist.longestRunDays >= 3) {
                Sticker("${artist.longestRunDays} days in a row", Pop.pink, size = 46, rotate = -3f, modifier = Modifier.css(scale = note, origin = LeftCenter))
            }
        }
    }
}

@Composable
private fun SlideFrame.ArtistRow(artist: WrappedArtist, rank: Int, delay: Int) {
    val enter = enter(delay, Springs.snappy)
    val image = assets.artistImage(artist.name, artist.imageUrl)
    val from = if (rank % 2 == 0) 1100f else -1100f
    val nameWidth = 600
    val shown = artist.name.uppercase()
    val fontSize = min(110.0, fitText(shown, nameWidth.toDouble(), measure))
    Row(
        Modifier.at(70, 450 + (rank - 1) * 280).size(940.dp, 250.dp).css(tx = (1 - enter) * from, rotate = if (rank % 2 == 0) 1.2f else -1.2f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Marker("$rank", 78, rotate = -8f, modifier = Modifier.width(90.dp))
        Cutout(image, 210, border = 9, rotate = if (rank % 2 == 0) 3f else -3f)
        Column(Modifier.padding(start = 36.dp).width(nameWidth.dp)) {
            CssText(shown, WrappedFonts.poster, fontSize, Ink, lineHeight = 0.95, wrap = false)
            Mono("${WrappedFormat.number(artist.minutes)} min", 30)
        }
    }
}

@Composable
fun SlideFrame.Artists() {
    val header = enter(0)
    Box(Modifier.fillMaxSize().background(Paper)) {
        Column(Modifier.at(70, 160).css(scale = header, origin = LeftTop).width(IntrinsicSize.Max)) {
            Marker("your", 64, rotate = -3f, modifier = Modifier.padding(bottom = 18.dp).fillMaxWidth())
            Poster("Top artists", 760, 170)
        }
        Doodle(Doodles.underline, 70, 400, 720, 48, Pop.pink, stroke = 10f, delay = 12)
        data.topArtists.take(5).forEachIndexed { i, artist -> ArtistRow(artist, i + 1, 12 + i * 7) }
    }
}
