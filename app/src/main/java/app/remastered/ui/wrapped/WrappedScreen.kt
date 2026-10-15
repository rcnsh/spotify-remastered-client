package app.remastered.ui.wrapped

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.remastered.core.warehouse.WrappedRange
import app.remastered.core.wrapped.WrappedFormat
import app.remastered.ui.AppState
import app.remastered.ui.Load
import app.remastered.ui.Route
import app.remastered.ui.components.EmptyMessage
import app.remastered.ui.components.ErrorMessage
import app.remastered.ui.components.Loading
import app.remastered.ui.components.ScreenPadding
import app.remastered.ui.load
import app.remastered.ui.screens.TopBar
import app.remastered.ui.theme.Palette
import app.remastered.ui.theme.Type

/**
 * Pick a Wrapped range (`Route.Wrapped`): the warehouse's offered ranges from `wrappedRanges()`,
 * each opening `Route.WrappedStory`. A read token can only open computed ranges, plus the preset
 * ones the warehouse will compute on request; those say so, because the first open can take a
 * minute. Shown only while the warehouse serves WarehouseFeature.WrappedRanges.
 */
@Composable
fun WrappedScreen(app: AppState) {
    val ranges = load(app.c.screens, "wrapped-ranges", maxAgeMs = 10 * 60_000) { app.c.warehouse.api.wrappedRanges() }
    LazyColumn(Modifier.fillMaxSize().background(Palette.base), contentPadding = ScreenPadding) {
        item { TopBar(app, title = "Your Wrapped", solid = true) }
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text(
                    "WRAPPED", fontFamily = WrappedFonts.poster, fontSize = 64.sp, lineHeight = 60.sp, color = Palette.text,
                )
                Text("Your listening, any stretch of it, told the way Spotify tells it once a year.", style = Type.bodyRegular, color = Palette.sub, modifier = Modifier.padding(top = 6.dp))
            }
        }
        when (val s = ranges.state) {
            Load.Loading -> item { Loading() }
            is Load.Failed -> item { ErrorMessage(s, ranges.retry) }
            is Load.Done -> if (s.value.isEmpty()) {
                item { EmptyMessage("Nothing to wrap yet", "The warehouse hasn't offered any ranges.") }
            } else {
                items(s.value, key = { "${it.from}|${it.to}" }) { RangeRow(it) { app.open(Route.WrappedStory(it.from, it.to)) } }
            }
        }
    }
}

@Composable
private fun RangeRow(range: WrappedRange, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp).clip(RoundedCornerShape(14.dp))
            .background(Palette.surface).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(range.label, style = Type.headline, color = Palette.text)
            Text(
                "${WrappedFormat.dayName(range.from, true)} – ${WrappedFormat.dayName(range.to, true)}",
                style = Type.caption, color = Palette.sub, modifier = Modifier.padding(top = 2.dp),
            )
            if (!range.computed) {
                Text("Open (computes, may take a minute)", style = Type.caption, color = Palette.dim, modifier = Modifier.padding(top = 4.dp))
            }
        }
        Text(
            if (range.computed) "PLAY" else "OPEN", fontFamily = WrappedFonts.poster, fontSize = 20.sp, fontWeight = FontWeight.Normal,
            color = Palette.onChipOn,
            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (range.computed) Pop.acid else Palette.chipOn).padding(horizontal = 16.dp, vertical = 6.dp),
        )
    }
}
