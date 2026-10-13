package app.remastered.ui.wrapped

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.remastered.ui.AppState
import app.remastered.ui.Route
import app.remastered.ui.load
import app.remastered.ui.screens.PlaceholderScreen
import app.remastered.ui.theme.Palette
import app.remastered.ui.theme.Type

/**
 * Pick a Wrapped range (`Route.Wrapped`) from `wrappedRanges()`; each opens `Route.WrappedStory`.
 * Shown only while the warehouse serves WarehouseFeature.WrappedRanges.
 * TODO(wrapped): the real screen.
 */
@Composable
fun WrappedScreen(app: AppState) {
    val ranges = load(Unit) { app.c.warehouse.api.wrappedRanges() }
    PlaceholderScreen(app, "Your Wrapped", "a card per range; uncomputed ones may take a minute.", ranges) { list ->
        list.forEach { r ->
            item {
                Text("${r.label}${if (r.computed) "" else " (not computed yet)"}", style = Type.body, color = Palette.text,
                    modifier = Modifier.fillMaxWidth().clickable { app.open(Route.WrappedStory(r.from, r.to)) }.padding(horizontal = 20.dp, vertical = 14.dp))
            }
        }
    }
}
