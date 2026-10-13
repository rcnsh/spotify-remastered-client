package app.remastered

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import app.remastered.core.PlaybackState
import app.remastered.spotify.auth.SpotifyAuth
import app.remastered.spotify.auth.Tokens
import app.remastered.spotify.web.RequestLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What Settings → Developer shows and shares. Nothing here reads or prints a token: sessions
 * are described by expiry and scopes only.
 */
object Diagnostics {
    const val SPOTIFY_PACKAGE = "com.spotify.music"

    data class Info(val label: String, val value: String)

    fun appVersion(context: Context): String {
        val p = context.packageManager.getPackageInfo(context.packageName, 0)
        return "${p.versionName} (${p.longVersionCode})"
    }

    /** The installed Spotify app's version, or null if it isn't installed (or isn't visible to us). */
    fun spotifyVersion(context: Context): String? = try {
        context.packageManager.getPackageInfo(SPOTIFY_PACKAGE, 0).let { "${it.versionName} (${it.longVersionCode})" }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    fun build(context: Context) = listOf(
        Info("Version", appVersion(context)),
        Info("Build type", BuildConfig.BUILD_TYPE + if (BuildConfig.DEBUG) " (debuggable)" else ""),
        Info("Package", context.packageName),
    )

    fun device() = listOf(
        Info("Device", "${Build.MANUFACTURER} ${Build.MODEL}"),
        Info("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"),
        Info("ABIs", Build.SUPPORTED_ABIS.joinToString(", ")),
        Info("Emulator", if (AppContainer.isEmulator) "yes (App Remote can't connect: no logged-in Spotify)" else "no"),
    )

    fun config() = listOf(
        Info("Client id", BuildConfig.SPOTIFY_CLIENT_ID.ifEmpty { "not set" }),
        Info("Redirect URI", BuildConfig.SPOTIFY_REDIRECT_URI),
        Info("Client secret", if (BuildConfig.SPOTIFY_CLIENT_SECRET.isEmpty()) "not set (PKCE only)" else "set"),
        Info("Spicy Lyrics key", if (BuildConfig.SPICY_LYRICS_KEY.isEmpty()) "not set (no lyrics)" else "set"),
    )

    /** Requested scopes the session wasn't granted. */
    fun missingScopes(t: Tokens): List<String> {
        val granted = t.scope.split(' ').filter { it.isNotBlank() }.toSet()
        return SpotifyAuth.SCOPES.filterNot { it in granted }
    }

    fun session(t: Tokens?, now: Long = System.currentTimeMillis()): List<Info> {
        if (t == null) return listOf(Info("Session", "signed out"))
        val missing = missingScopes(t)
        return listOf(
            Info("Access token", expiry(t.expiresAtEpochMs, now)),
            Info("Refresh", if (t.usesClientSecret) "client secret" else "PKCE"),
            Info("Scopes", "${SpotifyAuth.SCOPES.size - missing.size} of ${SpotifyAuth.SCOPES.size} granted"),
        ) + if (missing.isEmpty()) emptyList() else listOf(Info("Missing", missing.joinToString(", ")))
    }

    fun expiry(atEpochMs: Long, now: Long): String {
        val s = (atEpochMs - now) / 1000
        return when {
            atEpochMs <= 0 -> "expired (forced)"
            s <= 0 -> "expired ${-s / 60} min ago; refreshes on next request"
            else -> "valid for ${s / 60} min ${s % 60} s (until ${time(atEpochMs)})"
        }
    }

    fun time(epochMs: Long): String = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date(epochMs))

    fun player(state: PlaybackState?, connection: Any): String = buildString {
        appendLine("connection = $connection")
        if (state == null) { append("state = none yet"); return@buildString }
        val t = state.track
        appendLine("track      = ${t?.uri ?: "none"}")
        if (t != null) appendLine("             ${t.name} · ${t.artistNames} · ${t.durationMs} ms${if (t.liked) " · liked" else ""}")
        appendLine("context    = ${state.context?.let { "${it.uri} (${it.type}) ${it.title}" } ?: "none"}")
        appendLine("paused     = ${state.isPaused}   speed = ${state.speed}")
        appendLine("position   = ${state.positionMs} ms @ ${state.positionAtMs}")
        appendLine("shuffle    = ${state.shuffle}   repeat = ${state.repeat}")
        append("can        = seek:${state.canSeek} next:${state.canSkipNext} prev:${state.canSkipPrevious}")
    }

    fun requests(entries: List<RequestLog.Entry>) = entries.joinToString("\n") { e ->
        "${time(e.atEpochMs)} ${e.method} ${e.host}${e.path}${e.query?.let { "?$it" }.orEmpty()} → ${e.status ?: e.error} ${e.tookMs}ms"
    }

    /** A plain-text report for bug reports. Contains no tokens. */
    fun report(context: Context, c: AppContainer): String = buildString {
        fun section(title: String, rows: List<Info>) {
            appendLine("## $title")
            rows.forEach { appendLine("${it.label}: ${it.value}") }
            appendLine()
        }
        appendLine("Remastered diagnostics, ${Date()}")
        appendLine()
        section("Build", build(context))
        section("Device", device())
        section("Spotify app", listOf(Info("Installed", spotifyVersion(context) ?: "no")))
        section("Config", config())
        section("Session", session(c.spotify.auth.session()))
        appendLine("## Player")
        appendLine(player(c.player.state.value, c.player.connection.value))
        appendLine()
        appendLine("## Liked index")
        appendLine("${c.liked.tracks.value.size} songs, complete=${c.liked.complete.value}")
        appendLine()
        appendLine("## Recent requests (newest first)")
        appendLine(requests(c.spotify.requests.entries.value.take(100)))
    }

    /** This process's logcat (an app may always read its own), last [maxLines] lines. */
    suspend fun logcat(maxLines: Int = 3_000): String = withContext(Dispatchers.IO) {
        val proc = ProcessBuilder("logcat", "-d", "-v", "threadtime", "--pid=${Process.myPid()}")
            .redirectErrorStream(true).start()
        try {
            proc.inputStream.bufferedReader().readLines().takeLast(maxLines).joinToString("\n")
        } finally {
            proc.destroy()
        }
    }
}
