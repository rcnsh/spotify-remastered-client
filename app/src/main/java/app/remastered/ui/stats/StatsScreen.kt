package app.remastered.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.remastered.core.warehouse.AlbumYear
import app.remastered.core.warehouse.ArtistPlays
import app.remastered.core.warehouse.ArtistShare
import app.remastered.core.warehouse.ArtistShift
import app.remastered.core.warehouse.DailyPlays
import app.remastered.core.warehouse.Eras
import app.remastered.core.warehouse.OnRepeat
import app.remastered.core.warehouse.Period
import app.remastered.core.warehouse.Rhythm
import app.remastered.core.warehouse.Streak
import app.remastered.core.warehouse.StreakSet
import app.remastered.core.warehouse.TopAlbum
import app.remastered.core.warehouse.TopAlbums
import app.remastered.core.warehouse.TopArtists
import app.remastered.core.warehouse.TopOverTime
import app.remastered.core.warehouse.TopTracks
import app.remastered.core.warehouse.WarehouseFeature
import app.remastered.ui.AppState
import app.remastered.ui.Route
import app.remastered.ui.components.Artwork
import app.remastered.ui.components.Card
import app.remastered.ui.components.Chip
import app.remastered.ui.components.ScreenPadding
import app.remastered.ui.components.SubText
import app.remastered.ui.components.TitleText
import app.remastered.ui.components.dotted
import app.remastered.ui.history.HistoryIcons
import app.remastered.ui.history.Panel
import app.remastered.ui.history.PlayRow
import app.remastered.ui.history.SaveAsPlaylistChip
import app.remastered.ui.history.Stat
import app.remastered.ui.history.Swatch
import app.remastered.ui.history.WLoaded
import app.remastered.ui.history.WarehouseSection
import app.remastered.ui.history.loadWarehouse
import app.remastered.ui.history.playTrack
import app.remastered.ui.history.playingUri
import app.remastered.ui.history.plays
import app.remastered.ui.history.warehouseSupports
import app.remastered.ui.screens.TopBar
import app.remastered.ui.theme.Palette
import app.remastered.ui.theme.Type
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Listening stats from the warehouse (`Route.Stats`). Each section loads on its own, so one stat
 * that fails or is still being computed doesn't blank the page.
 */
@Composable
fun StatsScreen(app: AppState) {
    val api = app.c.warehouse.api
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone) }
    var preset by rememberSaveable { mutableStateOf<RangePreset?>(RangePreset.Month) }
    var custom by remember { mutableStateOf<DayRange?>(null) }
    var picking by remember { mutableStateOf(false) }
    val range = preset?.resolve(today) ?: custom ?: RangePreset.Month.resolve(today)

    val daily = loadWarehouse(range) { api.daily(range.from, range.to, zone) }
    val artists = loadWarehouse(range) { api.topArtists(range.from, range.to, zone, 10) }
    // An older warehouse has no /api/top-tracks: the section hides (learned from the check or a 404).
    val songsServed = app.warehouseSupports(WarehouseFeature.TopTracks)
    val songs = loadWarehouse(range, songsServed) { if (songsServed) api.topTracks(range.from, range.to, zone, TOP_SONGS) else null }
    val repeat = loadWarehouse(Unit) { api.onRepeat() }
    val rhythm = loadWarehouse(Unit) { api.rhythm() }
    val overTime = loadWarehouse(Unit) { api.topOverTime() }
    val eras = loadWarehouse(Unit) { api.eras() }
    val albums = loadWarehouse(Unit) { api.topAlbums() }

    Column(Modifier.fillMaxSize().background(Palette.base)) {
        TopBar(app, title = "Your stats", solid = true)
        LazyColumn(Modifier.weight(1f), contentPadding = ScreenPadding) {
            item(key = "range") {
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    items(RangePreset.entries, key = { it.name }) { p -> Chip(p.label, selected = preset == p, onClick = { preset = p }) }
                    item(key = "custom") {
                        Chip(if (preset == null && custom != null) rangeText(custom!!) else "Custom", selected = preset == null,
                            onClick = { picking = true }, icon = HistoryIcons.Calendar)
                    }
                }
            }
            item(key = "daily") { DailySection(app, daily, range) }
            item(key = "artists") { TopArtistsSection(app, artists) }
            if (songsServed) item(key = "songs") { TopSongsSection(app, songs, range, today) }
            item(key = "repeat") { OnRepeatSection(app, repeat, today) }
            item(key = "rhythm") { RhythmSection(rhythm) }
            item(key = "over-time") { OverTimeSection(overTime) }
            item(key = "eras") { ErasSection(eras) }
            item(key = "albums") { AlbumsSection(albums) }
        }
    }

    if (picking) RangePickerDialog(custom ?: range, today, onDismiss = { picking = false }) { picked ->
        picking = false
        custom = picked
        preset = null
    }
}

private val Short = DateTimeFormatter.ofPattern("d MMM")
private val ShortYear = DateTimeFormatter.ofPattern("d MMM yyyy")

// --- Plays per day ---

@Composable
private fun DailySection(app: AppState, data: WLoaded<DailyPlays>, range: DayRange) {
    WarehouseSection("Plays per day", data) { d ->
        val by = bucketingFor(range.days)
        val buckets = remember(d) { bucket(d.days, by) }
        val total = d.days.sumOf { it.plays }
        val busiest = d.days.maxByOrNull { it.plays }
        val top = niceCeiling(buckets.maxOfOrNull { it.plays } ?: 0)
        Panel {
            Row(Modifier.fillMaxWidth()) {
                Stat("%,d".format(total), "plays", Modifier.weight(1f))
                Stat("%,d".format(Math.round(total.toDouble() / d.days.size.coerceAtLeast(1))), "a day", Modifier.weight(1f))
                Stat(busiest?.takeIf { it.plays > 0 }?.day?.format(if (range.from.year == range.to.year) Short else ShortYear) ?: "–", "busiest", Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Text("%,d".format(top), style = Type.caption, color = Palette.dim, modifier = Modifier.weight(1f))
                Text(when (by) { Bucketing.Day -> "Tap a day to open it"; Bucketing.Week -> "Weekly · tap to open"; Bucketing.Month -> "Monthly · tap to open" },
                    style = Type.caption, color = Palette.dim)
            }
            Bars(buckets.map { it.plays.toFloat() }, Modifier.fillMaxWidth().height(150.dp).padding(top = 6.dp), max = top.toFloat(), gridLines = 2) { i ->
                app.open(Route.HistoryDay(buckets[i].end))
            }
            if (buckets.isNotEmpty()) AxisLabels(
                listOfNotNull(bucketLabel(buckets.first(), Bucketing.Day, range.days), buckets.getOrNull(buckets.size / 2)?.let { bucketLabel(it, Bucketing.Day, range.days) },
                    bucketLabel(buckets.last(), Bucketing.Day, range.days)).distinct(),
                Modifier.padding(top = 6.dp),
            )
        }
    }
}

// --- Top artists ---

@Composable
private fun TopArtistsSection(app: AppState, data: WLoaded<TopArtists>) {
    val history = app.warehouseSupports(WarehouseFeature.ArtistHistory)
    WarehouseSection("Top artists", data) { t ->
        if (t.artists.isEmpty()) Empty("No plays in this range.")
        val max = t.artists.maxOfOrNull { it.plays }?.coerceAtLeast(1) ?: 1
        t.artists.forEachIndexed { i, a -> ArtistBar(i + 1, a, max) { openArtist(app, a, history) } }
    }
}

private fun openArtist(app: AppState, a: ArtistPlays, history: Boolean) = when {
    history -> app.open(Route.HistoryArtist(a.id?.takeIf { a.uri != null }, a.name))
    a.uri != null -> app.openUri(a.uri)
    else -> Unit
}

@Composable
private fun ArtistBar(rank: Int, a: ArtistPlays, max: Int, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$rank", style = Type.label, color = Palette.sub, modifier = Modifier.width(28.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                TitleText(a.name, modifier = Modifier.weight(1f))
                Text(plays(a.plays), style = Type.caption, color = Palette.sub, modifier = Modifier.padding(start = 8.dp))
            }
            ShareBar(a.plays.toFloat() / max, Modifier.fillMaxWidth().padding(top = 6.dp))
        }
    }
}

// --- Top songs ---

/** How many top songs a range loads (and a playlist from them holds); the first [SONGS_SHOWN] show until expanded. */
private const val TOP_SONGS = 50
private const val SONGS_SHOWN = 10

@Composable
private fun TopSongsSection(app: AppState, data: WLoaded<TopTracks?>, range: DayRange, today: LocalDate) {
    val playing = app.playingUri()
    val trackHistory = app.warehouseSupports(WarehouseFeature.TrackHistory)
    WarehouseSection("Top songs", data) { t ->
        val tracks = t?.tracks.orEmpty()
        if (tracks.isEmpty()) Empty("No plays in this range.")
        SaveAsPlaylistChip(app, rangePlaylistName("Top songs", range, today), tracks.map { it.uri }, Modifier.padding(start = 20.dp, bottom = 6.dp))
        var all by remember(t) { mutableStateOf(false) }
        tracks.take(if (all) tracks.size else SONGS_SHOWN).forEachIndexed { i, s ->
            PlayRow(s.imageUrl, s.name, dotted(s.artist, plays(s.plays)),
                onClick = { app.playTrack(s.uri) },
                onMore = if (trackHistory && s.uri != null) ({ app.open(Route.HistoryTrack(s.id!!)) }) else null,
                playing = s.uri != null && s.uri == playing, leading = "${i + 1}", trailing = "")
        }
        if (!all && tracks.size > SONGS_SHOWN) {
            Chip("Show all ${tracks.size}", selected = false, onClick = { all = true }, modifier = Modifier.padding(start = 20.dp, top = 8.dp))
        }
    }
}

// --- On repeat ---

@Composable
private fun OnRepeatSection(app: AppState, data: WLoaded<OnRepeat>, today: LocalDate) {
    val playing = app.playingUri()
    val trackHistory = app.warehouseSupports(WarehouseFeature.TrackHistory)
    WarehouseSection("On repeat", data, action = data.value?.let { "Last ${it.windowDays} days" }) { r ->
        if (r.tracks.isEmpty() && r.albums.isEmpty()) Empty("Nothing's on repeat right now: a song needs 5+ plays on 3+ days.")
        SaveAsPlaylistChip(app, "On repeat · ${today.format(ShortYear)}", r.tracks.map { it.uri }, Modifier.padding(start = 20.dp, bottom = 6.dp))
        r.tracks.forEach { t ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    PlayRow(t.imageUrl, t.name, dotted(t.artists, "${plays(t.plays)} on ${t.days} days"),
                        onClick = { app.playTrack(t.uri) },
                        onMore = if (trackHistory) ({ app.open(Route.HistoryTrack(t.trackId)) }) else null,
                        playing = t.uri != null && t.uri == playing, trailing = "")
                }
                Bars(t.daily.map { it.toFloat() }, Modifier.padding(end = 20.dp).width(56.dp).height(24.dp))
            }
        }
        if (r.albums.isNotEmpty()) {
            Text("Albums", style = Type.body, color = Palette.text, modifier = Modifier.padding(start = 20.dp, top = 18.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 12.dp)) {
                items(r.albums, key = { it.name + it.artist }) { a ->
                    Card(a.name, a.artist, a.imageUrl, {}, size = 132.dp)
                }
            }
        }
    }
}

// --- Rhythm ---

@Composable
private fun RhythmSection(data: WLoaded<Rhythm>) {
    WarehouseSection("Rhythm", data) { r ->
        val scopes = remember(r) { rhythmScopes(r) }
        var scope by remember(r) { mutableStateOf(scopes.first()) }
        ChipRow(scopes, scope, { if (it is RhythmScope.Year && it.partial) "${it.year} so far" else it.label }) { scope = it }

        val streaks = streaksIn(r, scope)
        if (streaks.isNotEmpty()) {
            Text("Streaks", style = Type.body, color = Palette.text, modifier = Modifier.padding(start = 20.dp, top = 18.dp))
            Row(Modifier.padding(horizontal = 20.dp).padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                streaks.forEach { StreakCard(it, Modifier.weight(1f)) }
            }
        }

        val eras = erasIn(r, scope)
        var era by remember(r, scope) { mutableStateOf(defaultEra(eras)) }
        if (eras.isNotEmpty()) {
            Text("When you listen", style = Type.body, color = Palette.text, modifier = Modifier.padding(start = 20.dp, top = 22.dp))
            if (eras.size > 1) ChipRow(eras, era ?: eras.last(), { it.label }) { era = it }
        }
        era?.let { e ->
            Panel(Modifier.padding(top = 10.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(e.label, style = Type.body, color = Palette.text, modifier = Modifier.weight(1f))
                    SubText(monthSpan(e.from, e.to))
                }
                val hours = e.hourShare
                if (hours != null && hours.size == 24) {
                    val peak = hours.indices.maxBy { hours[it] }
                    SubText("Most at %02d:00 (%s of plays)".format(peak, percent(hours[peak])), Modifier.padding(top = 4.dp))
                    HourChart(hours, Modifier.padding(top = 12.dp))
                } else Empty(e.hoursAvailableFrom?.let { "Listening by hour shows from ${it.format(DateTimeFormatter.ofPattern("MMM yyyy"))}." }
                    ?: "Listening by hour needs four weeks of this era.", padded = false)
                val grid = e.grid
                if (grid != null && grid.size == 7) {
                    Text("By weekday and hour", style = Type.label, color = Palette.sub, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
                    WeekHeatmap(grid)
                } else if (e.gridAvailableFrom != null) {
                    SubText("The weekday grid shows from ${e.gridAvailableFrom.format(DateTimeFormatter.ofPattern("MMM yyyy"))}.", Modifier.padding(top = 12.dp), color = Palette.dim)
                }
            }
        }
    }
}

@Composable
private fun StreakCard(s: StreakSet, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(Palette.surface).padding(12.dp)) {
        Text("${s.threshold}+ a day", style = Type.label, color = Palette.accentLight)
        val best = s.longest
        Text(best?.let(::streakLength) ?: "–", style = Type.sectionSmall, color = Palette.text, modifier = Modifier.padding(top = 6.dp))
        SubText("longest")
        best?.let { SubText(shortMonthSpan(it.from, it.to), color = Palette.dim) }
        Text(percent(s.activeShare) + " of days", style = Type.caption, color = Palette.sub, modifier = Modifier.padding(top = 8.dp))
        if (s.currentWeeks > 0) Text("Now ${s.currentWeeks} wk", style = Type.caption, color = Palette.accentLight)
    }
}

private fun streakLength(s: Streak) = when {
    s.days != null -> if (s.days == 1) "1 day" else "${s.days} days"
    else -> "${s.weeks}+ wk"
}

// --- Top over time ---

@Composable
private fun OverTimeSection(data: WLoaded<TopOverTime>) {
    WarehouseSection("Top over time", data) { t ->
        var years by rememberSaveable { mutableStateOf(false) }
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Quarters", selected = !years, onClick = { years = false })
            Chip("Years", selected = years, onClick = { years = true })
        }
        val periods = (if (years) t.years else t.quarters).reversed()
        if (periods.isEmpty()) Empty("Not enough history yet.")
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 14.dp)) {
            items(periods, key = { it.period }) { PeriodCard(it) }
        }
        if (t.leaders.isNotEmpty() && t.quarters.size > 1) {
            Text("Biggest artists' share by quarter", style = Type.body, color = Palette.text, modifier = Modifier.padding(start = 20.dp, top = 22.dp))
            Panel(Modifier.padding(top = 10.dp)) {
                val bands = remember(t) { stackShares(t.leaders, t.quarters.size) }
                Text("100%", style = Type.caption, color = Palette.dim)
                StackedArea(bands, Modifier.fillMaxWidth().height(150.dp).padding(top = 4.dp))
                AxisLabels(listOf(periodLabel(t.quarters.first()), periodLabel(t.quarters.last())), Modifier.padding(top = 6.dp))
                Legend(t.leaders.map { it.name })
            }
        }
    }
}

private fun periodLabel(p: Period) = p.period.replace("-", " ") + if (p.partial) " (so far)" else ""

@Composable
private fun PeriodCard(p: Period) {
    Column(Modifier.width(250.dp).clip(RoundedCornerShape(18.dp)).background(Palette.surface).padding(14.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(periodLabel(p), style = Type.body, color = Palette.text, modifier = Modifier.weight(1f))
            SubText(plays(p.plays))
        }
        p.artists.take(5).forEach { a ->
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                TitleText(a.name, Type.label, modifier = Modifier.weight(1f))
                Text(percent(a.share), style = Type.caption, color = Palette.sub, modifier = Modifier.padding(start = 6.dp))
            }
            ShareBar(a.share.toFloat() / (p.artists.first().share.toFloat().takeIf { it > 0 } ?: 1f), Modifier.fillMaxWidth().padding(top = 4.dp))
        }
        if (p.tracks.isNotEmpty()) Text("Songs", style = Type.label, color = Palette.sub, modifier = Modifier.padding(top = 14.dp))
        p.tracks.take(3).forEach { s ->
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(s.imageUrl, Modifier.width(36.dp).height(36.dp), RoundedCornerShape(7.dp))
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    TitleText(s.name, Type.label)
                    SubText(dotted(s.artist, plays(s.plays)))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend(names: List<String>) {
    FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        names.forEachIndexed { i, n ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Swatch(SeriesColors[i % SeriesColors.size])
                Text(n, style = Type.caption, color = Palette.sub, modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

// --- Eras ---

@Composable
private fun ErasSection(data: WLoaded<Eras>) {
    WarehouseSection("Eras", data) { e ->
        val s = e.summary
        val since = e.latestEra?.let { "Since ${it.label}" } ?: "Since the move"
        if (e.moveMonth == null) {
            Empty("The warehouse has one era, so there's no move to compare across.")
        } else if (!e.ready || s == null) {
            Empty("Comparing eras needs at least a whole month since the move (${monthSpan(e.moveMonth, e.moveMonth)}), and history from before it.")
        } else {
            Panel {
                Text(since, style = Type.label, color = Palette.sub)
                SubText(dotted(e.latest?.let { monthSpan(it.from, it.to) }, "against " + (e.before?.let { monthSpan(it.from, it.to) } ?: "the months before")), color = Palette.dim)
                Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Stat(percent(s.knownArtistShare), "to artists you knew", Modifier.weight(1f))
                    Stat("%,d".format(s.newArtists), "new of %,d artists".format(s.artists), Modifier.weight(1f))
                }
                Text("Taste shift", style = Type.label, color = Palette.sub, modifier = Modifier.padding(top = 16.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    ShareBar(s.change.toFloat(), Modifier.weight(1f))
                    Text(percent(s.change), style = Type.label, color = Palette.text, modifier = Modifier.padding(start = 10.dp))
                }
                val tc = s.typicalChange
                Text("Usually ${percent(tc.median)} between back-to-back windows (${percent(tc.min)}–${percent(tc.max)}). " +
                    if (tc.exceededBy == 0) "None of ${tc.windows} earlier ones changed more." else "${tc.exceededBy} of ${tc.windows} earlier ones changed more.",
                    style = Type.caption, color = Palette.sub, modifier = Modifier.padding(top = 6.dp))
            }
            ShareColumns("Before", e.topBefore, "Since", e.topLatest)
            ShiftList("Rising", e.risers)
            ShiftList("Falling", e.fallers)
        }
        if (e.topEarlier.isNotEmpty()) {
            Text(e.latestEra?.let { "Everything before ${it.label}" } ?: "The whole era before", style = Type.body, color = Palette.text,
                modifier = Modifier.padding(start = 20.dp, top = 22.dp))
            Column(Modifier.padding(top = 4.dp)) {
                val max = e.topEarlier.first().share.toFloat().takeIf { it > 0 } ?: 1f
                e.topEarlier.forEach { ShareRow(it.artist, percent(it.share), it.share.toFloat() / max) }
            }
        }
    }
}

@Composable
private fun ShareColumns(leftTitle: String, left: List<ArtistShare>, rightTitle: String, right: List<ArtistShare>) {
    Row(Modifier.padding(horizontal = 20.dp).padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        listOf(leftTitle to left, rightTitle to right).forEach { (title, list) ->
            Column(Modifier.weight(1f)) {
                Text(title, style = Type.body, color = Palette.text)
                list.take(6).forEach { a ->
                    Row(Modifier.padding(top = 8.dp)) {
                        TitleText(a.artist, Type.label, modifier = Modifier.weight(1f))
                        Text(percent(a.share), style = Type.caption, color = Palette.sub, modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ShiftList(title: String, shifts: List<ArtistShift>) {
    if (shifts.isEmpty()) return
    Text(title, style = Type.body, color = Palette.text, modifier = Modifier.padding(start = 20.dp, top = 20.dp))
    shifts.take(5).forEach { s ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TitleText(s.artist, Type.label, modifier = Modifier.weight(1f))
            Text("${percent(s.before)} → ${percent(s.after)}", style = Type.caption,
                color = if (s.after >= s.before) Palette.progress else Palette.heart)
        }
    }
}

@Composable
private fun ShareRow(name: String, value: String, fraction: Float) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            TitleText(name, Type.label, modifier = Modifier.weight(1f))
            Text(value, style = Type.caption, color = Palette.sub)
        }
        ShareBar(fraction, Modifier.fillMaxWidth().padding(top = 5.dp))
    }
}

// --- Albums ---

/** Most-played albums, all time or one year. */
@Composable
private fun AlbumsSection(data: WLoaded<TopAlbums>) {
    WarehouseSection("Top albums", data, action = data.value?.let { "${it.minSongs}+ songs each" }) { t ->
        val years = remember(t) { t.years.sortedByDescending { it.year } }
        var year by remember(t) { mutableStateOf<AlbumYear?>(null) }
        ChipRow(listOf<AlbumYear?>(null) + years, year, { it?.let { y -> if (y.partial) "${y.year} so far" else "${y.year}" } ?: "All time" }) { year = it }
        val list = year?.albums ?: t.albums
        if (list.isEmpty()) Empty("No albums yet.")
        list.forEachIndexed { i, a -> AlbumRow(i + 1, a) }
    }
}

@Composable
private fun AlbumRow(rank: Int, a: TopAlbum) {
    Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$rank", style = Type.label, color = Palette.sub, modifier = Modifier.width(28.dp))
        Artwork(a.imageUrl, Modifier.width(48.dp).height(48.dp), RoundedCornerShape(9.dp))
        Column(Modifier.weight(1f).padding(start = 13.dp)) {
            TitleText(a.name)
            SubText(dotted(a.artist, plays(a.plays), "${a.songs} songs"), Modifier.padding(top = 3.dp))
        }
    }
}

// --- Shared bits ---

@Composable
private fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
        items(options.size) { i -> Chip(label(options[i]), selected = options[i] == selected, onClick = { onSelect(options[i]) }) }
    }
}

@Composable
private fun Empty(text: String, padded: Boolean = true) =
    Text(text, style = Type.caption, color = Palette.sub, modifier = if (padded) Modifier.padding(horizontal = 20.dp, vertical = 10.dp) else Modifier.padding(top = 10.dp))

/** A from–to picker for the chart's custom range, up to today. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangePickerDialog(initial: DayRange, today: LocalDate, onDismiss: () -> Unit, onPick: (DayRange) -> Unit) {
    fun utc(d: LocalDate) = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    fun local(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
    val last = utc(today)
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = utc(initial.from),
        initialSelectedEndDateMillis = utc(initial.to),
        yearRange = 2008..today.year,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= last
            override fun isSelectableYear(year: Int) = year <= today.year
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val a = state.selectedStartDateMillis
                if (a == null) onDismiss() else onPick(customRange(local(a), local(state.selectedEndDateMillis ?: a), today))
            }) { Text("Show", color = Palette.accentLight) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Palette.sub) } },
    ) {
        DateRangePicker(state, Modifier.weight(1f), title = {
            Text("Pick a range", style = Type.label, color = Palette.sub, modifier = Modifier.padding(start = 24.dp, top = 16.dp))
        })
    }
}
