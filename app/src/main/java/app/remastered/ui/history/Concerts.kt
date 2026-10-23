package app.remastered.ui.history

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.remastered.core.warehouse.Concert
import app.remastered.core.warehouse.Concerts
import app.remastered.core.warehouse.WarehouseFeature
import app.remastered.ui.AppState
import app.remastered.ui.Route
import app.remastered.ui.components.SectionHeader
import app.remastered.ui.components.SubText
import app.remastered.ui.components.TitleText
import app.remastered.ui.components.dotted
import app.remastered.ui.theme.Icons
import app.remastered.ui.theme.Palette
import app.remastered.ui.theme.Type
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// Upcoming gigs from the warehouse's daily Ticketmaster check (`/api/concerts`): an "On tour" row
// on Home and the artist's own gigs on the artist page. Both are extras: nothing shows without
// the warehouse, on a deployment without the route, when the request fails, or with no gigs.

/** Home's "On tour" row holds at most this many gigs. */
private const val HOME_CONCERTS = 10

/** The warehouse's concerts, or null while loading, unavailable or failed. */
@Composable
fun rememberConcerts(app: AppState): Concerts? {
    val on = app.warehouseSupports(WarehouseFeature.Concerts)
    return loadWarehouse(on) { if (on) app.c.warehouse.api.concerts() else null }.value
}

/** "On tour": the soonest upcoming gigs, as cards like Home's other rows. Nothing when there are none. */
fun LazyListScope.onTour(app: AppState, concerts: Concerts?, today: LocalDate) {
    val upcoming = concerts?.upcoming(today)?.take(HOME_CONCERTS)?.takeIf { it.isNotEmpty() } ?: return
    item(key = "on-tour") {
        Column(Modifier.padding(top = 30.dp)) {
            SectionHeader("On tour", action = concerts.regions.singleOrNull()?.label ?: "Near you")
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 14.dp)) {
                // Two gigs can share every field shown (two regions list the same event), so keys carry the position.
                itemsIndexed(upcoming, key = { i, c -> "tour-$i-${c.url ?: c.name}" }) { _, c ->
                    // The artist page lists the gigs with their ticket links; without a Spotify id, go straight to the tickets.
                    val artist = c.artists.firstNotNullOfOrNull { a -> a.uri?.let { a.id } }
                    ConcertCard(c, onClick = { if (artist != null) app.open(Route.Artist(artist)) else app.openTickets(c) })
                }
            }
        }
    }
}

/** The artist page's upcoming gigs for [id] / [name], each opening its ticket page. Nothing when there are none. */
@Composable
fun ArtistConcerts(app: AppState, id: String, name: String) {
    val concerts = rememberConcerts(app) ?: return
    val today = remember { LocalDate.now() }
    val gigs = remember(concerts, id, name) { concerts.upcoming(today, id, name) }
    if (gigs.isEmpty()) return
    Column(Modifier.padding(top = 22.dp)) {
        SectionHeader("On tour", action = count(gigs.size, "date"), small = true)
        Spacer(Modifier.height(6.dp))
        gigs.take(6).forEach { c -> ConcertRow(c, onClick = c.url?.let { { app.openTickets(c) } }) }
    }
}

/** A Home card: a date tile where other cards have artwork, then the bill and the place. */
@Composable
private fun ConcertCard(c: Concert, onClick: () -> Unit, size: Dp = 148.dp) {
    Column(Modifier.width(size).clickable(onClickLabel = "Open ${c.headliners}", onClick = onClick)) {
        DateTile(c, Modifier.size(size), big = true)
        TitleText(c.headliners, Type.body.copy(fontSize = Type.body.fontSize * .94f), modifier = Modifier.padding(top = 10.dp))
        SubText(dotted(c.venue, c.city).ifEmpty { c.name }, Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun ConcertRow(c: Concert, onClick: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().height(72.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DateTile(c, Modifier.size(52.dp), big = false)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            TitleText(c.venue ?: c.name)
            SubText(dotted(c.city, timeOf(c), statusOf(c)), Modifier.padding(top = 3.dp))
        }
        if (onClick != null) Icon(Icons.Forward, "Tickets", Modifier.size(20.dp), tint = Palette.sub)
    }
}

/** Month over day, like a ticket stub; "TBA" while there's no date. */
@Composable
private fun DateTile(c: Concert, modifier: Modifier, big: Boolean) {
    val shape = RoundedCornerShape(if (big) 14.dp else 10.dp)
    Box(
        modifier.clip(shape).background(Brush.linearGradient(listOf(Palette.raised, Palette.surface))).border(1.dp, Palette.line, shape),
        contentAlignment = Alignment.Center,
    ) {
        val d = c.date
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(d?.format(MonthFormat)?.uppercase() ?: "DATE", style = Type.tiny, color = Palette.accentLight)
            Text(d?.dayOfMonth?.toString() ?: "TBA", style = if (big) Type.display.copy(fontSize = Type.display.fontSize * 1.6f, lineHeight = Type.display.lineHeight * 1.6f) else Type.sectionSmall,
                color = Palette.text, maxLines = 1)
            if (big) Text(dotted(d?.format(WeekdayFormat), c.time).ifEmpty { " " }, style = Type.caption, color = Palette.sub, maxLines = 1)
        }
        val status = statusOf(c)
        if (big && status != null) Text(status, style = Type.tiny, color = Palette.text, maxLines = 1,
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(6.dp)).background(Palette.accentSoft).padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

private val MonthFormat = DateTimeFormatter.ofPattern("MMM")
private val WeekdayFormat = DateTimeFormatter.ofPattern("EEE")

/** "Sat 19:30", or the year too for a gig next year; null without a date. */
private fun timeOf(c: Concert): String? {
    val d = c.date ?: return null
    val day = d.format(DateTimeFormatter.ofPattern(if (d.year == LocalDate.now().year) "EEE d MMM" else "EEE d MMM yyyy"))
    return listOfNotNull(day, c.time).joinToString(" ")
}

/** Worth a note only when it isn't simply on sale. */
private fun statusOf(c: Concert): String? = when (c.status) {
    "postponed" -> "Postponed"
    "rescheduled" -> "Rescheduled"
    "offsale" -> "Off sale"
    else -> null
}

/** Opens [gig]'s ticket page in a Custom Tab (the browser if there's none). */
private fun AppState.openTickets(gig: Concert) {
    val url = gig.url ?: return message("Ticketmaster has no page for this one.")
    val uri = url.toUri()
    try {
        CustomTabsIntent.Builder().setShowTitle(true).build().apply { intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }.launchUrl(c.context, uri)
    } catch (_: ActivityNotFoundException) {
        runCatching { c.context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { message("Nothing can open that link") }
    }
}
