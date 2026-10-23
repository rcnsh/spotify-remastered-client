package app.remastered.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.remastered.core.SleepTimer
import app.remastered.ui.AppState
import app.remastered.ui.components.IconButton
import app.remastered.ui.theme.Icons
import app.remastered.ui.theme.Palette
import app.remastered.ui.theme.Type
import kotlinx.coroutines.delay

/** The sleep timer's time left, ticking once a second while it's counting; null while it's off or set to the song's end. */
@Composable
private fun rememberSleepRemaining(app: AppState): Long? {
    val mode by app.c.sleepTimer.mode.collectAsState()
    return produceState(app.c.sleepTimer.remainingMs(), mode) {
        while (mode is SleepTimer.Mode.After) {
            value = app.c.sleepTimer.remainingMs()
            delay(1000 - (value ?: 0) % 1000)
        }
        value = null
    }.value
}

private fun songOrEpisode(app: AppState) = if (app.c.player.state.value?.track?.isEpisode == true) "episode" else "song"

/**
 * Now Playing's sleep timer button: a moon, or while the timer runs, a pill with the time left
 * (or "End of song"). Opens [SleepTimerSheet]. Its own composable so the countdown recomposes
 * only this.
 */
@Composable
fun SleepTimerButton(app: AppState) {
    val mode by app.c.sleepTimer.mode.collectAsState()
    val remaining = rememberSleepRemaining(app)
    if (mode == null) {
        IconButton(Icons.Moon, "Sleep timer", { app.sleepTimerOpen = true }, tint = Color.White.copy(alpha = .8f), iconSize = 20.dp)
        return
    }
    val label = remaining?.let { formatTime(it + 999) } ?: "End of ${songOrEpisode(app)}"
    Row(Modifier.clip(CircleShape).background(Palette.accentSoft).clickable(onClickLabel = "Change sleep timer") { app.sleepTimerOpen = true }
        .semantics { contentDescription = "Sleep timer: $label" }.padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Moon, null, Modifier.size(16.dp), tint = Palette.accentLight)
        Text(label, style = Type.label.copy(fontSize = 12.5.sp), color = Palette.accentLight, modifier = Modifier.padding(start = 6.dp))
    }
}

/** Pick how long until playback pauses, or turn the timer off. Runs in NowPlayingService, so it works with the app closed. */
@Composable
fun SleepTimerSheet(app: AppState) {
    val timer = app.c.sleepTimer
    val mode by timer.mode.collectAsState()
    val remaining = rememberSleepRemaining(app)
    val what = songOrEpisode(app)
    fun then(action: () -> Unit) { app.sleepTimerOpen = false; action() }
    Sheet({ app.sleepTimerOpen = false }) {
        Text("Sleep timer", style = Type.sectionSmall.copy(fontSize = 19.sp), color = Palette.text)
        Text(when {
            mode == null -> "Pause playback after"
            remaining != null -> "Pauses in ${formatTime(remaining + 999)}"
            else -> "Pauses at the end of this $what"
        }, style = Type.caption, color = if (mode == null) Palette.sub else Palette.accentLight, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
        HorizontalDivider(color = Palette.line)
        SleepTimer.MINUTES.forEach { m ->
            SleepOption("$m minutes", (mode as? SleepTimer.Mode.After)?.minutes == m) { then { timer.start(m) } }
        }
        SleepOption("End of $what", mode == SleepTimer.Mode.EndOfTrack) { then { timer.startEndOfTrack() } }
        if (mode != null) {
            HorizontalDivider(color = Palette.line, modifier = Modifier.padding(vertical = 4.dp))
            Row(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(12.dp)).clickable { then { timer.cancel() } },
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Close, null, Modifier.size(22.dp), tint = Palette.sub)
                Text("Turn off timer", style = Type.body.copy(fontSize = 15.5.sp), color = Palette.text, modifier = Modifier.padding(start = 18.dp))
            }
        }
    }
}

@Composable
private fun SleepOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Type.body.copy(fontSize = 15.5.sp), color = if (selected) Palette.accentLight else Palette.text, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Check, "Selected", Modifier.size(22.dp), tint = Palette.accentLight)
    }
}
