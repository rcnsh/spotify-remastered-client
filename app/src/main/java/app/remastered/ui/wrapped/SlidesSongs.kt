package app.remastered.ui.wrapped

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.remastered.core.wrapped.Springs
import app.remastered.core.wrapped.WrappedFormat
import app.remastered.core.wrapped.fitText
import kotlin.math.max
import kotlin.math.min

// Web: remotion/slides/songs.tsx

@Composable
fun SlideFrame.Song() {
    val song = data.topSongs[0]
    val label = enter(10)
    val name = enter(22, Springs.bouncy)
    val stats = enter(44)
    Box(Modifier.fillMaxSize().background(Ink)) {
        HoloDisc(song.imageUrl, 800, Modifier.at(140, 130), speed = 1.4)
        Stack(bottom = 120, gap = 26) {
            Marker("your top song", 84, Pop.yellow, -6f, opacity = label)
            Column(Modifier.css(scale = name, origin = LeftCenter)) {
                PosterLines(song.name, 920, 2, 230, Paper)
                Body(song.artist, 52, 700, Paper, Modifier.padding(top = 16.dp))
            }
            Column(Modifier.css(ty = (1 - stats) * 40, opacity = stats)) {
                Sticker("played ${WrappedFormat.number(song.plays)} times", Pop.yellow, size = 58, rotate = -2f)
                song.peakMonth?.let { Mono("It peaked in ${WrappedFormat.monthName(it)}.", 38, Paper, Modifier.padding(top = 26.dp)) }
            }
        }
    }
}

/** A linear 0→1 ramp over 10 frames (web `ramp`). */
private fun ramp(frame: Int, delay: Int): Float = ((frame - delay) / 10f).coerceIn(0f, 1f)

@Composable
fun SlideFrame.Songs() {
    val songs = data.topSongs.take(5)
    val startAt = 14
    val holdFrames = (durationInFrames - startAt - 20) / songs.size
    val current = min(songs.size - 1, max(0, Math.floorDiv(frame - startAt, holdFrames)))
    val header = enter(0)
    Box(Modifier.fillMaxSize().background(Pop.blue)) {
        Column(Modifier.at(70, 150).css(scale = header, origin = LeftTop).width(IntrinsicSize.Max)) {
            Marker("your", 64, Paper, -3f, Modifier.padding(bottom = 18.dp).fillMaxWidth())
            CssText("TOP SONGS", WrappedFonts.poster, 150, Paper, lineHeight = 0.9, wrap = false)
        }
        ArtCarousel(songs.map { it.imageUrl }, 1080, 760, holdFrames, startAt, Modifier.at(0, 330))
        songs.forEachIndexed { i, song ->
            val lit = i == current
            val enter = ramp(frame, 20 + i * 5)
            val shown = song.name.uppercase()
            val size = min(76.0, fitText(shown, 700.0, measure))
            Row(Modifier.at(70, 1140 + i * 140).width(940.dp).css(tx = (1 - enter) * 300 + (if (lit) 18 else 0), opacity = enter * (if (lit) 1f else 0.55f))) {
                CssText("${i + 1}", WrappedFonts.marker, 64, if (lit) Pop.acid else Paper, Modifier.width(80.dp).alignByBaseline())
                Column(Modifier.width(720.dp).alignByBaseline()) {
                    CssText(shown, WrappedFonts.poster, size, Paper, lineHeight = 1.0, wrap = false)
                    Body("${song.artist} · ${WrappedFormat.number(song.plays)} plays", 30, 600, Paper)
                }
            }
        }
    }
}

@Composable
fun SlideFrame.Album() {
    val album = data.topAlbums[0]
    val card = enter(0, Springs.bouncy)
    val text = enter(20)
    val jitter = jitter("album", 1.2)
    Box(Modifier.fillMaxSize().background(Paper)) {
        Marker("your top album", 84, rotate = -5f, modifier = Modifier.at(80, 150))
        Cutout(assets.image(album.imageUrl), 740, border = 20, modifier = Modifier.at(130, 300).css(jitter.x, jitter.y, card, 5 + jitter.r))
        Tape(560, 300, 4f, 300, rgba(255, 225, 74, 0.8), "album-a")
        Doodle(Doodles.circle, 760, 900, 240, 240, Pop.red, stroke = 10f, delay = 30)
        Stack(bottom = 130, gap = 14, modifier = Modifier.css(ty = (1 - text) * 60, opacity = text)) {
            PosterLines(album.name, 920, 2, 200)
            Body(album.artist, 54, 800, Ink)
            Mono("${album.songs} songs · ${WrappedFormat.number(album.minutes)} minutes", 38)
        }
    }
}
