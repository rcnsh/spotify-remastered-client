package app.remastered.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.remastered.Diagnostics
import app.remastered.RemasteredApp
import app.remastered.core.AppSettings
import app.remastered.core.Connection
import app.remastered.core.model.best
import app.remastered.core.warehouse.WarehouseException
import app.remastered.core.warehouse.WarehouseState
import app.remastered.spotify.web.RequestLog
import app.remastered.ui.AppState
import app.remastered.ui.Route
import app.remastered.ui.components.Artwork
import app.remastered.ui.components.Chip
import app.remastered.ui.components.IconButton
import app.remastered.ui.components.ScreenPadding
import app.remastered.ui.components.SubText
import app.remastered.ui.components.TitleText
import app.remastered.ui.components.artworkTintCount
import app.remastered.ui.components.clearArtworkTints
import app.remastered.ui.theme.Icons
import app.remastered.ui.theme.Palette
import app.remastered.ui.wrapped.WrappedPreview
import app.remastered.BuildConfig
import app.remastered.ui.theme.Type
import app.remastered.warehouse.WarehouseConfig
import coil3.SingletonImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings: account, playback, library, storage, then everything a developer needs (build and
 * session info, token tools, the Web API request log, live player state, logs, reset), then About.
 */
@Composable
fun SettingsScreen(app: AppState) {
    val c = app.c
    var confirmReset by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().background(Palette.base), contentPadding = ScreenPadding) {
        item {
            Row(Modifier.statusBarsPadding().padding(start = 10.dp, end = 20.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(Icons.Back, "Back", { app.back() }, tint = Palette.text)
                Text("Settings", style = Type.title, color = Palette.text, modifier = Modifier.padding(start = 6.dp))
            }
        }
        item { AccountCard(app) }
        item { PlaybackGroup(app) }
        item { LibraryGroup(app) }
        item { LyricsGroup(app) }
        item { WarehouseGroup(app) }
        item { StorageGroup(app) }
        item { Group("Developer", "Tools for building and debugging Remastered. Nothing here shows a token.") { DeveloperRows(app) { confirmReset = true } } }
        item { SessionGroup(app) }
        item { RequestsGroup(app) }
        item { PlayerStateGroup(app) }
        item { UriToolGroup(app) }
        if (BuildConfig.DEBUG) item { WrappedPreviewGroup(app) }
        item { InfoGroup("Build", Diagnostics.build(c.context)) }
        item { InfoGroup("Device", Diagnostics.device()) }
        item { InfoGroup("Spotify developer app", Diagnostics.config(), copyable = true) }
        item { AboutGroup(app) }
    }
    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        containerColor = Palette.surface,
        title = { Text("Reset Remastered?", style = Type.sectionSmall, color = Palette.text) },
        text = { Text("Signs you out and deletes all settings, recent searches and cached images. Your Spotify library isn't touched.",
            style = Type.caption, color = Palette.sub) },
        confirmButton = {
            TextButton(onClick = {
                confirmReset = false
                val ctx = c.context.applicationContext
                c.scope.launch(Dispatchers.IO) { SingletonImageLoader.get(ctx).run { memoryCache?.clear(); diskCache?.clear() } }
                clearArtworkTints()
                (ctx as RemasteredApp).reset()
            }) { Text("Reset", color = Palette.heart) }
        },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
    )
}

// --- sections ---

@Composable
private fun AccountCard(app: AppState) {
    val me = app.me
    Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
        .background(Palette.surface).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(me?.images?.best(120)?.url, Modifier.size(56.dp), CircleShape)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            TitleText(me?.displayName ?: "Spotify account", Type.body.copy(fontSize = 17.sp))
            SubText(me?.let { "spotify:user:${it.id}" } ?: "Profile not loaded", Modifier.padding(top = 3.dp))
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (me != null) Chip("Open in Spotify", false, { app.viewUri("spotify:user:${me.id}") })
                else Chip("Retry", false, { app.reloadProfile() })
                Chip("Sign out", false, { app.c.screens.clear(); app.c.spotify.signOut() })
            }
        }
    }
}

@Composable
private fun PlaybackGroup(app: AppState) {
    val c = app.c
    val connection by c.player.connection.collectAsState()
    val ctx = LocalContext.current
    Group("Playback", "Remastered plays through the Spotify app on this phone (App Remote).") {
        val (status, detail) = when (val s = connection) {
            Connection.Connected -> "Connected" to "Controlling the Spotify app."
            Connection.Connecting -> "Connecting…" to null
            Connection.Idle -> "Not connected" to null
            Connection.SpotifyMissing -> "Spotify isn't installed" to "Install it from the Play Store and log in."
            Connection.NeedsAuth -> "Needs permission" to "Allow Remastered to control Spotify."
            is Connection.Failed -> "Can't connect" to s.message
        }
        Item("Spotify app", detail?.let { "$status. $it" } ?: status) {
            if (connection == Connection.Connected) Chip("Disconnect", false, { c.player.disconnect() })
            else Chip("Connect", true, { c.player.connect() })
        }
        Toggle("Connect when Remastered opens", "Otherwise, connect from here or the Home banner.", c.settings.autoConnect)
        Toggle("Remastered notification", "Lock screen and Quick Settings controls that open Remastered. " +
            "Keeps Remastered connected while music plays, with the app closed.", c.settings.mediaNotification)
        Item("Spotify's notification", "Turn off its playback notification so only Remastered's shows", onClick = {
            ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, Diagnostics.SPOTIFY_PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }) { Chevron() }
        Item("Devices", "Spotify Connect speakers, computers and TVs", onClick = { app.devicesOpen = true }) { Chevron() }
        Item("Open the Spotify app", Diagnostics.spotifyVersion(ctx)?.let { "Version $it" } ?: "Not installed", onClick = {
            ctx.packageManager.getLaunchIntentForPackage(Diagnostics.SPOTIFY_PACKAGE)?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                ?: app.message("Spotify isn't installed")
        }) { Chevron() }
    }
}

@Composable
private fun LibraryGroup(app: AppState) {
    val c = app.c
    val liked by c.liked.tracks.collectAsState()
    val complete by c.liked.complete.collectAsState()
    val recent by c.recentSearches.entries.collectAsState()
    Group("Library") {
        Toggle("Load Liked Songs at start", "Powers the ♥ marks in lists and \"In your library\" on artist pages. " +
            "Off: they load when you open Liked Songs or an artist.", c.settings.preloadLiked)
        Item("Liked Songs index", "%,d songs%s".format(liked.size, if (complete) "" else " (not fully loaded)")) {
            Chip("Reload", false, { c.liked.reload() })
        }
        Toggle("Save recent searches", "Kept on this phone only.", c.settings.saveRecentSearches)
        Item("Recent searches", if (recent.isEmpty()) "None" else "${recent.size} saved") {
            Chip("Clear", false, { c.recentSearches.clear(); app.message("Recent searches cleared") })
        }
        Item("Manage app access", "Remove Remastered from your Spotify account", onClick = {
            app.viewUri("https://www.spotify.com/account/apps/")
        }) { Chevron() }
    }
}

@Composable
private fun LyricsGroup(app: AppState) {
    val c = app.c
    val offset by c.settings.lyricsOffsetMs.value.collectAsState()
    var version by remember { mutableIntStateOf(0) }
    var stats by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    LaunchedEffect(version) { stats = withContext(Dispatchers.IO) { c.lyricsCache.stats() } }
    Group("Lyrics", "Synced lyrics from the Spicy Lyrics API, on Now Playing. The provider and the people who synced them are credited under the lyrics.") {
        Toggle("Show lyrics", null, c.settings.lyrics)
        Toggle("Romanization and translations", "Shown under each line when the lyrics have them.", c.settings.lyricsExtras)
        Item("Timing", when {
            offset > 0 -> "Lines show ${offset} ms early. Raise this if lyrics lag behind the song."
            offset < 0 -> "Lines show ${-offset} ms late. Lower this if lyrics run ahead of the song."
            else -> "In step with Spotify's position. Adjust if lyrics run ahead or behind."
        })
        FlowRow(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("− 100 ms", false, { c.settings.lyricsOffsetMs.set((offset - 100).coerceAtLeast(-2_000)) })
            Chip("+ 100 ms", false, { c.settings.lyricsOffsetMs.set((offset + 100).coerceAtMost(2_000)) })
            if (offset != 0) Chip("Reset", false, { c.settings.lyricsOffsetMs.reset() })
        }
        Item("Saved lyrics", stats?.let { "${it.first} songs, ${bytes(it.second)}. Each is kept at most 30 days." } ?: "…") {
            Chip("Clear", false, {
                c.scope.launch {
                    withContext(Dispatchers.IO) { c.lyricsCache.clear() }
                    version++
                    app.message("Saved lyrics cleared")
                }
            })
        }
    }
}

@Composable
private fun WarehouseGroup(app: AppState) {
    val warehouse = app.c.warehouse
    val config by warehouse.config.collectAsState()
    val state by warehouse.state.collectAsState()
    val missing by warehouse.missing.collectAsState()
    var url by remember(config) { mutableStateOf(config?.baseUrl.orEmpty()) }
    // Never shown: blank means "keep the saved token".
    var token by remember(config) { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    /** What's typed, falling back to the saved token; null (with a message) when it can't be used. */
    fun entered(): WarehouseConfig? {
        val base = WarehouseConfig.normalizeUrl(url) ?: run { result = "Enter the warehouse's https:// address."; return null }
        val t = token.trim().ifEmpty { config?.token.orEmpty() }.ifEmpty { result = "Enter a token."; return null }
        return WarehouseConfig(base, t)
    }

    fun test(cfg: WarehouseConfig) {
        result = "Testing…"
        scope.launch {
            val start = System.nanoTime()
            result = warehouse.test(cfg).fold(
                { play ->
                    "OK in ${(System.nanoTime() - start) / 1_000_000} ms. " +
                        (play?.let { "Latest play: ${it.name} · ${it.artists}, ${PLAYED_AT.format(it.playedAt)}." } ?: "No plays stored yet.")
                },
                { e -> (e as? WarehouseException)?.let { "${it.userMessage}${it.status?.let { s -> " (HTTP $s)" }.orEmpty()}" } ?: "Failed." },
            )
        }
    }

    Group("Listening history", "Your play history from your own music-warehouse, for History, Stats and Wrapped. " +
        "The token is stored encrypted on this phone and never shown or logged.") {
        val status = when (val s = state) {
            WarehouseState.NotConfigured -> "Not set up. History, Stats and Wrapped are hidden."
            WarehouseState.Checking -> "Checking…"
            WarehouseState.Ready -> "Connected."
            WarehouseState.Unauthorized -> "Token rejected. Enter a new one."
            is WarehouseState.Unreachable -> s.message
        }
        Item("Status", if (missing.isEmpty() || state !is WarehouseState.Ready) status
            else "$status This warehouse doesn't serve ${missing.joinToString { it.name }} yet; those features stay hidden.")
        Field(url, { url = it }, "https://music-api.example.com")
        Field(token, { token = it }, if (config != null) "Token saved. Type to replace it." else "READ_TOKEN", secret = true)
        FlowRow(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Save", true, {
                entered()?.let { cfg ->
                    warehouse.save(cfg)
                    token = ""
                    test(cfg)
                }
            })
            Chip("Test connection", false, { entered()?.let(::test) })
            if (config != null) Chip("Remove", false, {
                warehouse.clear()
                result = null
                app.message("Warehouse removed")
            })
        }
        result?.let { Mono(it) }
    }
}

private val PLAYED_AT = java.time.format.DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(java.time.ZoneId.systemDefault())

/** A one-line text field in the style of the uri tool; [secret] masks what's typed. */
@Composable
private fun Field(value: String, onChange: (String) -> Unit, placeholder: String, secret: Boolean = false) {
    Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .background(Palette.raised).padding(horizontal = 12.dp, vertical = 11.dp)) {
        if (value.isEmpty()) Text(placeholder, style = MonoStyle, color = Palette.dim)
        BasicTextField(value, onChange, singleLine = true, textStyle = MonoStyle.copy(color = Palette.text),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            cursorBrush = SolidColor(Palette.accent), modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun StorageGroup(app: AppState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    var sizes by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    LaunchedEffect(version) {
        sizes = withContext(Dispatchers.IO) {
            SingletonImageLoader.get(ctx).let { (it.memoryCache?.size ?: 0L) to (it.diskCache?.size ?: 0L) }
        }
    }
    Group("Storage") {
        Item("Image cache", sizes?.let { "${bytes(it.second)} on disk · ${bytes(it.first)} in memory · $artworkTintCount colours" } ?: "…") {
            Chip("Clear", false, {
                scope.launch {
                    withContext(Dispatchers.IO) { SingletonImageLoader.get(ctx).run { memoryCache?.clear(); diskCache?.clear() } }
                    clearArtworkTints()
                    version++
                    app.message("Image cache cleared")
                }
            })
        }
    }
}

@Composable
private fun DeveloperRows(app: AppState, onReset: () -> Unit) {
    val c = app.c
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Toggle("Log Web API requests to logcat", "Tag WebApi: method, path, status, time. Never headers or bodies.", c.settings.logRequests)
    Toggle("Show uris in song menus", null, c.settings.showUris)
    Item("Share diagnostics", "Build, device, config, session, player state and recent requests as text", onClick = {
        share(ctx, "Remastered diagnostics", Diagnostics.report(ctx, c))
    }) { Chevron() }
    Item("Share app logs", "This app's logcat (last 3,000 lines)", onClick = {
        scope.launch {
            runCatching { Diagnostics.logcat() }
                .onSuccess { share(ctx, "Remastered logs", it.ifEmpty { "(logcat was empty)" }) }
                .onFailure { app.message("Couldn't read logcat") }
        }
    }) { Chevron() }
    Item("Android app info", "Permissions, storage, force stop", onClick = {
        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }) { Chevron() }
    Item("Restart", "Rebuilds the app: reconnects App Remote, reloads the library. Keeps you signed in.") {
        Chip("Restart", false, { (ctx.applicationContext as RemasteredApp).restart() })
    }
    Item("Reset Remastered", "Sign out and delete all local data", titleColor = Palette.heart) {
        Chip("Reset", false, onReset)
    }
}

@Composable
private fun SessionGroup(app: AppState) {
    val c = app.c
    var version by remember { mutableIntStateOf(0) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1_000) } }
    val session = remember(version, now / 5_000) { c.spotify.auth.session() }
    var probe by remember { mutableStateOf<String?>(null) }
    Group("Web API session") {
        Diagnostics.session(session, now).forEach { Item(it.label, it.value, mono = it.label == "Missing") }
        FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Refresh token now", false, {
                app.launchAuth { c.spotify.auth.tokenProvider.refresh(); version++; app.message("Token refreshed") }
            })
            Chip("Expire access token", false, {
                c.spotify.auth.expireAccessToken(); version++
                app.message("Access token expired; the next request refreshes it")
            })
            Chip("GET /me", false, {
                probe = "…"
                app.launchAuth {
                    val start = System.nanoTime()
                    probe = runCatching { c.catalog.me() }.fold(
                        { "OK in ${(System.nanoTime() - start) / 1_000_000} ms: ${it.displayName} (${it.id})" },
                        { "Failed: ${it.message}" },
                    )
                    version++
                }
            })
        }
        probe?.let { Mono(it) }
        Item("Spotify developer dashboard", "Client id, redirect URIs, allow-listed users", onClick = {
            app.viewUri("https://developer.spotify.com/dashboard")
        }) { Chevron() }
    }
}

@Composable
private fun RequestsGroup(app: AppState) {
    val log = app.c.spotify.requests
    val entries by log.entries.collectAsState()
    val ctx = LocalContext.current
    var all by remember { mutableStateOf(false) }
    val failed = entries.count { !it.ok }
    Group("Web API requests", "${entries.size} recorded this session, $failed not OK. Newest first.") {
        if (entries.isEmpty()) Item("No requests yet", null)
        (if (all) entries else entries.take(12)).forEach { RequestRow(it) }
        FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (entries.size > 12) Chip(if (all) "Show fewer" else "Show all ${entries.size}", false, { all = !all })
            Chip("Copy", false, { copy(ctx, "Requests", Diagnostics.requests(entries)); app.message("Copied") })
            Chip("Clear", false, { log.clear() })
        }
    }
}

@Composable
private fun RequestRow(e: RequestLog.Entry) {
    val color = when {
        e.status == null -> Palette.heart
        e.status == 429 -> Color(0xFFF0C341)
        !e.ok -> Palette.heart
        else -> Palette.progress
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Text((e.status?.toString() ?: "ERR"), style = MonoStyle, color = color, modifier = Modifier.padding(end = 10.dp))
        Column(Modifier.weight(1f)) {
            Text("${e.method} ${e.path.removePrefix("/v1")}${e.query?.let { "?$it" }.orEmpty()}", style = MonoStyle, color = Palette.text, maxLines = 2)
            Text("${Diagnostics.time(e.atEpochMs)} · ${e.host} · ${e.tookMs} ms${e.error?.let { " · $it" }.orEmpty()}",
                style = MonoStyle.copy(fontSize = 10.5.sp), color = Palette.dim)
        }
    }
}

@Composable
private fun PlayerStateGroup(app: AppState) {
    val state by app.c.player.state.collectAsState()
    val connection by app.c.player.connection.collectAsState()
    val ctx = LocalContext.current
    val text = Diagnostics.player(state, connection)
    Group("Player state", "Live, as App Remote reports it (with optimistic updates applied).") {
        Mono(text)
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Copy", false, { copy(ctx, "Player state", text); app.message("Copied") })
            state?.track?.let { t -> Chip("Copy track uri", false, { copy(ctx, "Track uri", t.uri); app.message("Copied") }) }
        }
    }
}

@Composable
private fun UriToolGroup(app: AppState) {
    var uri by remember { mutableStateOf("") }
    val trimmed = uri.trim()
    val route = Route.of(trimmed)
    Group("Open a Spotify uri", "spotify:album:…, spotify:playlist:…, spotify:track:… or an open.spotify.com link.") {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(Palette.raised).padding(horizontal = 12.dp, vertical = 11.dp)) {
            if (uri.isEmpty()) Text("spotify:…", style = MonoStyle, color = Palette.dim)
            BasicTextField(uri, { uri = fromLink(it) }, singleLine = true, textStyle = MonoStyle.copy(color = Palette.text),
                cursorBrush = SolidColor(Palette.accent), modifier = Modifier.fillMaxWidth())
        }
        Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Open", route != null, { route?.let(app::open) ?: app.message("No screen for that uri") })
            Chip("Play", false, { if (trimmed.startsWith("spotify:")) app.play(trimmed) else app.message("Not a spotify: uri") })
            Chip("Queue", false, {
                if (trimmed.startsWith("spotify:track:") || trimmed.startsWith("spotify:episode:")) { app.c.player.queue(trimmed); app.message("Added to queue") }
                else app.message("Only tracks and episodes can be queued")
            })
            Chip("Lyrics", false, {
                if (trimmed.startsWith("spotify:track:")) app.lyricsPreview = trimmed
                else app.message("Lyrics need a spotify:track: uri")
            })
        }
    }
}

/** Debug builds only: open any range's Wrapped story (the `wrapped_preview` extra does the same before sign-in). */
@Composable
private fun WrappedPreviewGroup(app: AppState) {
    var range by remember { mutableStateOf("2025-01-01:2025-12-31") }
    val parsed = WrappedPreview.parse(range)
    Group("Wrapped preview", "Any closed range as from:to. A read token can only open computed ranges.") {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(Palette.raised).padding(horizontal = 12.dp, vertical = 11.dp)) {
            BasicTextField(range, { range = it }, singleLine = true, textStyle = MonoStyle.copy(color = Palette.text),
                cursorBrush = SolidColor(Palette.accent), modifier = Modifier.fillMaxWidth())
        }
        Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
            Chip("Open story", parsed != null, { parsed?.let { app.open(Route.WrappedStory(it.from, it.to)) } ?: app.message("Use YYYY-MM-DD:YYYY-MM-DD") })
        }
    }
}

@Composable
private fun AboutGroup(app: AppState) {
    val ctx = LocalContext.current
    Group("About") {
        Item("Remastered", "Version ${Diagnostics.appVersion(ctx)}")
        Item("Not affiliated with Spotify", "Playback goes through the Spotify app (App Remote SDK); your library comes from the Spotify Web API. " +
            "Spotify's terms and your plan's limits apply.")
        Item("Lyrics", "From the Spicy Lyrics API (spicylyrics.org) and its community, credited under each song's lyrics. Not affiliated with Spicy Lyrics.")
        Item("Open-source software", "Spotify Android SDK (Apache 2.0), Jetpack Compose, Kotlin coroutines and serialization, OkHttp, Coil, Gson (Apache 2.0); Figtree font (OFL 1.1).")
    }
}

// --- building blocks ---

private val MonoStyle = Type.caption.copy(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, lineHeight = 16.sp)

@Composable
private fun Group(title: String, note: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 26.dp)) {
        Text(title.uppercase(), style = Type.tiny.copy(fontSize = 12.sp), color = Palette.dim, modifier = Modifier.padding(start = 4.dp))
        if (note != null) Text(note, style = Type.caption, color = Palette.sub, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
        Column(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Palette.surface).padding(vertical = 4.dp),
            content = content)
    }
}

@Composable
private fun Item(
    title: String,
    subtitle: String?,
    onClick: (() -> Unit)? = null,
    mono: Boolean = false,
    titleColor: Color = Palette.text,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.body, color = titleColor)
            if (subtitle != null) Text(subtitle, style = if (mono) MonoStyle else Type.caption, color = Palette.sub, modifier = Modifier.padding(top = 2.dp))
        }
        if (trailing != null) Box(Modifier.padding(start = 12.dp)) { trailing() }
    }
}

@Composable
private fun Toggle(title: String, subtitle: String?, flag: AppSettings.Flag) {
    val on by flag.value.collectAsState()
    Item(title, subtitle, onClick = { flag.set(!on) }) {
        Switch(on, { flag.set(it) }, colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White, checkedTrackColor = Palette.accent,
            uncheckedThumbColor = Palette.sub, uncheckedTrackColor = Palette.raised, uncheckedBorderColor = Palette.line,
        ))
    }
}

@Composable
private fun InfoGroup(title: String, rows: List<Diagnostics.Info>, copyable: Boolean = false) {
    val ctx = LocalContext.current
    Group(title) {
        rows.forEachIndexed { i, r ->
            if (i > 0) HorizontalDivider(color = Palette.line, modifier = Modifier.padding(horizontal = 16.dp))
            Row(Modifier.fillMaxWidth().then(if (copyable) Modifier.clickable { copy(ctx, r.label, r.value) } else Modifier)
                .padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(r.label, style = Type.caption, color = Palette.sub, modifier = Modifier.weight(.38f))
                Text(r.value, style = MonoStyle, color = Palette.text, modifier = Modifier.weight(.62f))
            }
        }
        if (copyable) Text("Tap a row to copy it.", style = Type.caption, color = Palette.dim, modifier = Modifier.padding(16.dp, 4.dp, 16.dp, 10.dp))
    }
}

@Composable
private fun Mono(text: String) {
    Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .background(Palette.bg).horizontalScroll(rememberScrollState()).padding(12.dp)) {
        Text(text, style = MonoStyle, color = Palette.text)
    }
}

@Composable
private fun Chevron() = Icon(Icons.Forward, null, Modifier.size(20.dp), tint = Palette.dim)

// --- helpers ---

private fun bytes(n: Long): String = when {
    n >= 1 shl 20 -> "%.1f MB".format(n / 1048576.0)
    n >= 1 shl 10 -> "%.0f KB".format(n / 1024.0)
    else -> "$n B"
}

/** Accepts open.spotify.com links as well as uris. */
private fun fromLink(input: String): String {
    val m = Regex("""open\.spotify\.com/(?:intl-[a-z]+/)?(track|album|playlist|artist|episode|show)/([A-Za-z0-9]+)""").find(input)
    return m?.let { "spotify:${it.groupValues[1]}:${it.groupValues[2]}" } ?: input
}

private fun copy(ctx: Context, label: String, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun share(ctx: Context, title: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, title).putExtra(Intent.EXTRA_TEXT, text)
    ctx.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Opens [uri] (a spotify: uri or a web link) in whatever app handles it. */
private fun AppState.viewUri(uri: String) {
    runCatching { c.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { message("Nothing can open that link") }
}

/** Like [AppState.launch], but also reports sign-in failures (refresh rejected, network) instead of crashing. */
private fun AppState.launchAuth(block: suspend () -> Unit) = c.scope.launch {
    try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        message(e.message ?: AppState.describe(e))
    } finally {
        c.spotify.recheck()
    }
}
