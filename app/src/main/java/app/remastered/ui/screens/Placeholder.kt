package app.remastered.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.remastered.ui.AppState
import app.remastered.ui.Load
import app.remastered.ui.Loaded
import app.remastered.ui.components.ErrorMessage
import app.remastered.ui.components.Loading
import app.remastered.ui.components.ScreenPadding
import app.remastered.ui.theme.Palette
import app.remastered.ui.theme.Type

// TODO(warehouse features): scaffolding for the History, Stats and Wrapped screens. Delete this
// file once none of them uses it.

/** A titled page with a back button, a note saying what goes here, and whatever [data] loaded. */
@Composable
fun <T> PlaceholderScreen(app: AppState, title: String, todo: String, data: Loaded<T>, content: LazyListScope.(T) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().background(Palette.base), contentPadding = ScreenPadding) {
        item { TopBar(app, title = title, solid = true) }
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text(title, style = Type.title, color = Palette.text)
                Text("TODO: $todo", style = Type.caption, color = Palette.dim, modifier = Modifier.padding(top = 6.dp))
            }
        }
        when (val s = data.state) {
            Load.Loading -> item { Loading() }
            is Load.Failed -> item { ErrorMessage(s.message, data.retry) }
            is Load.Done -> content(s.value)
        }
    }
}

/** One plain line of placeholder output. */
fun LazyListScope.line(text: String) = item {
    Text(text, style = Type.bodyRegular, color = Palette.sub, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
}
