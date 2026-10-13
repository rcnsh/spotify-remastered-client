package app.remastered.ui.wrapped

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import app.remastered.core.warehouse.WrappedMonth
import app.remastered.core.wrapped.Easing
import app.remastered.core.wrapped.Springs
import app.remastered.core.wrapped.WrappedFormat
import app.remastered.core.wrapped.WrappedTimeline
import app.remastered.core.wrapped.interpolateClamped
import app.remastered.core.wrapped.jsRound
import app.remastered.core.wrapped.spring
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// Web: remotion/slides/later.tsx

private fun countUp(frame: Int, from: Int, to: Int, value: Int) =
    jsRound(interpolateClamped(frame.toDouble(), from.toDouble(), to.toDouble(), 0.0, value.toDouble(), Easing.out(Easing.cubic)))

@Composable
fun SlideFrame.Age() {
    val age = data.listeningAge!!
    val head = enter(0)
    val note = enter(60, Springs.bouncy)
    val decades = age.decades.filter { it.share >= 0.02 }.sortedByDescending { it.share }.take(5).sortedBy { it.decade }
    val maxShare = decades.maxOfOrNull { it.share } ?: 1.0
    Box(Modifier.fillMaxSize().background(Pop.yellow)) {
        Body("Your listening\nage is", 76, 900, Ink, Modifier.at(80, 170).css(opacity = head), lineHeight = 1.0)
        Poster(countUp(frame, 10, 50, age.age).toString(), 720, 900, modifier = Modifier.at(60, 380))
        Doodle(Doodles.circle, 0, 320, 780, 700, stroke = 1.8f, delay = 48, stretch = true)
        Stack(bottom = 110, gap = 14) {
            val window = age.window?.let { "${it.first}–${it.last}" } ?: "${age.medianYear}"
            Marker(
                "You played like someone who came of age\non music from $window.", 58, rotate = -3f,
                modifier = Modifier.padding(bottom = 30.dp).css(scale = note, origin = LeftCenter),
            )
            decades.forEachIndexed { i, decade ->
                val grow = interpolateClamped((frame - 70 - i * 6).toDouble(), 0.0, 18.0, easing = Easing.out(Easing.cubic)).toFloat()
                val width = max(320.0, 860 * (decade.share / maxShare)).toFloat()
                Box(Modifier.width((width * grow).dp).clipToBounds()) {
                    Torn(
                        Modifier.wrapContentWidth(Alignment.Start, unbounded = true).width(width.dp),
                        Ink, "decade${decade.decade}", 6.0, PaddingValues(horizontal = 22.dp, vertical = 10.dp),
                    ) {
                        Mono("${decade.decade}s · ${jsRound(decade.share * 100)}%", 34, Paper, wrap = false)
                    }
                }
            }
        }
    }
}

@Composable
fun SlideFrame.Discoveries() {
    val top = data.discoveries.top.firstOrNull()
    val image = top?.let { assets.artistImage(it.name, it.imageUrl) }
    val label = enter(46, Springs.bouncy)
    val card = enter(70, Springs.bouncy)
    Box(Modifier.fillMaxSize().background(Ink)) {
        Marker("you discovered", 80, Paper, -5f, Modifier.at(80, 160))
        Poster(WrappedFormat.number(countUp(frame, 8, 46, data.discoveries.artists)), 940, 460, Pop.green, Modifier.at(70, 290))
        Stack(bottom = 120, gap = 60) {
            Column(Modifier.css(scale = label, origin = LeftCenter)) {
                Poster("new artists", 640, 170, Paper)
                Mono("and ${WrappedFormat.number(data.discoveries.songs)} songs you'd never played", 38, Paper, Modifier.padding(top = 16.dp))
            }
            if (top != null) {
                Box(Modifier.css(ty = (1 - card) * 900)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Cutout(image, 400, border = 14, rotate = -5f)
                        Column(Modifier.padding(start = 40.dp).width(480.dp)) {
                            Sticker("biggest find", Pop.green, size = 42, rotate = -4f)
                            PosterLines(top.name, 480, 3, 130, Paper, Modifier.padding(top = 22.dp))
                            Mono("${WrappedFormat.number(top.minutes)} minutes", 32, Paper, Modifier.padding(top = 14.dp))
                        }
                    }
                    Tape(40, 10, -40f, 180, seed = "disc")
                }
            }
        }
    }
}

@Composable
private fun SlideFrame.MonthCard(month: WrappedMonth, modifier: Modifier) {
    Column(modifier.width(640.dp)) {
        Cutout(assets.image(month.imageUrl), 640, border = 16)
        Column(Modifier.padding(top = 30.dp)) {
            Poster(WrappedFormat.monthName(month.month), 600, 150)
            month.topArtist?.let { Body(it, 46, 800, Ink, Modifier.padding(top = 10.dp)) }
            month.topSong?.let { Body(it, 36, 500, Ink) }
            Mono("${WrappedFormat.number(month.minutes)} minutes · ${month.month.year}", 32, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** A carousel that snaps from month to month with a springy settle. */
@Composable
fun SlideFrame.Months() {
    val months = sampleMonths(data.months)
    val hold = (durationInFrames - 30).toDouble() / months.size
    val index = min(months.size - 1, floor(max(0, frame - 10) / hold).toInt())
    val since = frame - 10 - index * hold
    val settle = if (index == 0) 1.0 else spring(since, WrappedTimeline.FPS, Springs.snappy)
    val position = index - 1 + settle
    val gap = 720
    val header = enter(0)
    Box(Modifier.fillMaxSize().background(Paper)) {
        Column(Modifier.at(80, 150).css(scale = header, origin = LeftTop).width(IntrinsicSize.Max)) {
            Marker("month by month", 64, rotate = -3f, modifier = Modifier.padding(bottom = 18.dp).fillMaxWidth())
            Poster("Your mixtape", 760, 160)
        }
        val left = 220 - max(0.0, position) * gap
        months.forEachIndexed { i, month ->
            val x = left + i * gap
            // Cards wholly off-frame draw nothing either way; skipping them keeps frames cheap.
            if (x > -700 && x < WrappedTimeline.WIDTH) MonthCard(month, Modifier.at(x, 520))
        }
        Row(Modifier.at(80, 1800), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            months.indices.forEach { i -> Box(Modifier.size(54.dp, 12.dp).background(if (i == index) Ink else rgba(11, 11, 11, 0.2))) }
        }
    }
}

@Composable
fun SlideFrame.Streak() {
    val streak = data.streak!!
    val days = countUp(frame, 8, 50, streak.days)
    val words = enter(30, Springs.bouncy)
    // One dot per day, capped so the grid fits; each dot stands for several days when it can't.
    val dots = min(streak.days, 72)
    val perRow = 12
    val lit = jsRound(days.toDouble() / streak.days * dots)
    Box(Modifier.fillMaxSize().background(Pop.orange)) {
        Confetti(1080, 1920, listOf(Ink, Paper, Pop.yellow), 30, "streak")
        Marker("your longest streak", 80, rotate = -5f, modifier = Modifier.at(80, 160))
        Poster(days.toString(), 700, 520, modifier = Modifier.at(70, 280))
        Stack(bottom = 120, gap = 50) {
            Column(Modifier.css(scale = words, origin = LeftCenter)) {
                Poster("days in a row", 900, 200)
                Mono("${WrappedFormat.dayName(streak.from, true)} – ${WrappedFormat.dayName(streak.to, true)}", 38, modifier = Modifier.padding(top = 18.dp))
            }
            val w = (920f - 14 * (perRow - 1)) / perRow
            val h = w / 2.2f
            val rows = ceil(dots / perRow.toDouble()).toInt()
            Canvas(Modifier.size(920.dp, (rows * h + (rows - 1) * 14).coerceAtLeast(0f).dp)) {
                val u = 1.dp.toPx()
                for (i in 0 until dots) {
                    val col = i % perRow
                    val row = i / perRow
                    drawRoundRect(
                        if (i < lit) Ink else rgba(11, 11, 11, 0.15),
                        Offset(col * (w + 14) * u, row * (h + 14) * u), Size(w * u, h * u), CornerRadius(10 * u),
                    )
                }
            }
        }
    }
}

/**
 * The share card: a cassette insert of the whole range. Spotify's has top artists, top songs,
 * minutes and top genre; there's no genre data here, so listening age (or the top album) takes
 * that corner.
 */
@Composable
fun SlideFrame.Summary() {
    val top = data.topArtists[0]
    val image = assets.artistImage(top.name, top.imageUrl)
    val card = enter(0, Springs.soft)
    fun reveal(i: Int) = interpolateClamped((frame - 14 - i * 4).toDouble(), 0.0, 10.0).toFloat()
    val corner = data.listeningAge?.let { "Listening age" to it.age.toString() } ?: data.topAlbums.firstOrNull()?.let { "Top album" to it.name }
    val title = WrappedFormat.summaryTitle(data.from, data.to)

    @Composable
    fun list(items: List<String>, offset: Int) = items.forEachIndexed { i, item ->
        Row(Modifier.css(opacity = reveal(offset + i))) {
            CssText("${i + 1}", WrappedFonts.mono, 32, Ink, Modifier.width(34.dp).padding(top = 5.dp), lineHeight = 1.3)
            Box(Modifier.padding(start = 14.dp).width(360.dp).clipToBounds()) {
                CssText(item, WrappedFonts.body, 42, Ink, Modifier.wrapContentWidth(Alignment.Start, unbounded = true), weight = 800, lineHeight = 1.3, wrap = false)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Ink)) {
        Box(
            Modifier.at(60, 130)
                .css(ty = (1 - card) * 1900, rotate = (1 - card) * 8 - 1)
                .size(960.dp, 1660.dp)
                .dropShadow(RectangleShape, Shadow(radius = 60.dp, color = Color.Black, offset = DpOffset(0.dp, 30.dp), alpha = 0.5f))
                .background(Paper),
        ) {
            Column(Modifier.padding(56.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                    Cutout(image, 420, border = 12, rotate = -3f)
                    Column(Modifier.padding(top = 10.dp)) {
                        Mono("WRAPPED", 28)
                        Poster(title, 360, 170)
                    }
                }
                Row(Modifier.padding(top = 90.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                    Column(Modifier.width(404.dp)) {
                        Mono("TOP ARTISTS", 28, modifier = Modifier.padding(bottom = 14.dp))
                        list(data.topArtists.take(5).map { it.name }, 0)
                    }
                    Column(Modifier.width(404.dp)) {
                        Mono("TOP SONGS", 28, modifier = Modifier.padding(bottom = 14.dp))
                        list(data.topSongs.take(5).map { it.name }, 5)
                    }
                }
                Row(Modifier.padding(top = 110.dp).css(opacity = reveal(11)), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                    Column(Modifier.width(404.dp)) {
                        Mono("MINUTES LISTENED", 28)
                        Poster(WrappedFormat.number(data.totals.minutes), 404, 150)
                    }
                    corner?.let { (label, value) ->
                        Column(Modifier.width(404.dp)) {
                            Mono(label.uppercase(), 28)
                            PosterLines(value, 404, 2, 150)
                        }
                    }
                }
            }
            Doodle(Doodles.star, 720, 300, 110, 110, Pop.pink, stroke = 7f, delay = 40)
            Row(
                Modifier.align(Alignment.BottomStart).padding(start = 56.dp, end = 56.dp, bottom = 48.dp).fillMaxWidth().css(opacity = reveal(13)),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
            ) {
                Mono("${WrappedFormat.number(data.totals.artists)} ARTISTS · ${WrappedFormat.number(data.totals.songs)} SONGS · ${WrappedFormat.number(data.totals.daysListened)} DAYS", 26)
                Sticker("wrapped", Pop.green, size = 34, rotate = -3f)
            }
        }
    }
}
