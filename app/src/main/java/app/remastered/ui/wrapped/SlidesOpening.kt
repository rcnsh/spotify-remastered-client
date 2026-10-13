package app.remastered.ui.wrapped

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.remastered.core.wrapped.Easing
import app.remastered.core.wrapped.Springs
import app.remastered.core.wrapped.WrappedFormat
import app.remastered.core.wrapped.interpolate
import app.remastered.core.wrapped.interpolateClamped
import app.remastered.core.wrapped.jsRound
import app.remastered.core.wrapped.Extrapolate

// Web: remotion/slides/opening.tsx

@Composable
fun SlideFrame.Intro() {
    val title = WrappedFormat.rangeTitle(data.from, data.to)
    val art = data.topSongs.firstOrNull()?.imageUrl ?: data.topAlbums.firstOrNull()?.imageUrl
    val big = enter(18, Springs.bouncy)
    val word = enter(28, Springs.snappy)
    val jitter = jitter("intro", 1.2)
    Box(Modifier.fillMaxSize().background(Ink)) {
        HoloDisc(art, 1000, Modifier.at(40, 180))
        Marker(
            "your", 92, Paper, -8f, Modifier.at(90, 150),
            opacity = interpolate(frame.toDouble(), doubleArrayOf(6.0, 14.0), doubleArrayOf(0.0, 1.0), right = Extrapolate.Clamp).toFloat(),
        )
        Doodle(Doodles.star, 860, 170, 130, 130, Pop.acid, stroke = 7f, delay = 10)
        Doodle(Doodles.squiggle, 70, 1070, 260, 120, Pop.pink, stroke = 9f, delay = 24)
        Stack(bottom = 130, gap = 10) {
            Poster(title.big, 920, 420, Paper, Modifier.css(jitter.x, jitter.y, big, jitter.r, LeftBottom))
            Box(Modifier.css(scale = word, origin = LeftTop)) {
                Sticker("Wrapped", Pop.acid, size = 120, rotate = -4f)
                Tape(560, -10, 18f, 180, rgba(212, 255, 58, 0.7), "intro-b")
            }
            title.small?.let { Mono(it, 38, Paper, Modifier.padding(top = 16.dp).css(opacity = word)) }
        }
        Tape(180, 1060, -12f, seed = "intro-a")
    }
}

@Composable
fun SlideFrame.Minutes() {
    val title = WrappedFormat.rangeTitle(data.from, data.to)
    val count = jsRound(interpolateClamped(frame.toDouble(), 14.0, 70.0, 0.0, data.totals.minutes.toDouble(), Easing.out(Easing.cubic)))
    val intro = enter(4)
    val label = enter(70, Springs.bouncy)
    val footer = enter(96)
    val days = jsRound(data.totals.minutes / 1440.0)
    Box(Modifier.fillMaxSize().background(Paper)) {
        Confetti(1080, 1920, listOf(Pop.green, Pop.pink, Pop.blue, Pop.yellow), 40, "minutes")
        Torn(
            Modifier.at(0, 170).css(tx = (1 - intro) * -1100, rotate = -2f).width(980.dp),
            Ink, "minutes-top", padding = PaddingValues(start = 90.dp, top = 34.dp, end = 44.dp, bottom = 34.dp),
        ) {
            Body("${if (title.small != null) "In that time," else "In ${title.big},"}\nyou listened for", 62, 800, Paper, lineHeight = 1.05)
        }
        Poster(WrappedFormat.number(count), 940, 480, modifier = Modifier.at(70, 620))
        Stack(left = 60, bottom = 130, width = 960, gap = 70) {
            Sticker("minutes", Pop.green, size = 150, rotate = -4f, modifier = Modifier.padding(start = 30.dp).css(scale = label, origin = LeftCenter))
            Torn(Modifier.css(ty = (1 - footer) * 500).width(960.dp), Ink, "minutes-foot") {
                val lines = buildString {
                    append(if (days >= 1) "That's ${WrappedFormat.number(days)} ${if (days == 1) "whole day" else "whole days"}, back to back." else "Every minute counted.")
                    data.biggestDay?.let { append("\nYour biggest day: ${WrappedFormat.dayName(it.date)}, ${WrappedFormat.number(it.minutes)} minutes.") }
                }
                Mono(lines, 38, Paper)
            }
        }
        Doodle(Doodles.arrow, 820, 1100, 130, 130, Ink, stroke = 9f, delay = 84)
    }
}

@Composable
fun SlideFrame.Clock() {
    val (who, peakHour) = WrappedFormat.persona(data.hours)
    val title = enter(70, Springs.bouncy)
    val line = enter(84)
    Box(Modifier.fillMaxSize().background(Ink)) {
        Marker("your listening clock", 80, Paper, -5f, Modifier.at(90, 170))
        Turntable(data.hours, peakHour, 1080, 900, Pop.acid, Color(0xFFD8D4CB), Modifier.at(0, 300))
        Mono("midnight at the back · the day runs clockwise", 30, Color(0xFF9A978F), Modifier.at(0, 1190).width(1080.dp), align = TextAlign.Center)
        Stack(left = 90, bottom = 130, width = 900, gap = 20) {
            // A block inside a shrink-to-fit box: the marker spans the poster's width, as on the web.
            Column(Modifier.css(scale = title, origin = LeftBottom).width(IntrinsicSize.Max)) {
                Marker("you were", 64, Pop.acid, -3f, Modifier.padding(bottom = 16.dp).fillMaxWidth())
                PosterLines(who.title, 900, 2, 200, Paper)
            }
            Column(Modifier.css(opacity = line)) {
                Body(who.line, 44, 700, Paper)
                Mono("Peak listening around ${WrappedFormat.hourLabel(peakHour)}", 34, Pop.acid, Modifier.padding(top = 14.dp))
            }
        }
    }
}
