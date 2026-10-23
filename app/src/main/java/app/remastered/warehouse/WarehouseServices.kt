package app.remastered.warehouse

import app.remastered.core.warehouse.Play
import app.remastered.core.warehouse.Warehouse
import app.remastered.core.warehouse.WarehouseException
import app.remastered.core.warehouse.WarehouseFeature
import app.remastered.core.warehouse.WarehouseException.Problem
import app.remastered.core.warehouse.WarehouseState
import app.remastered.spicylyrics.LyricsEndpoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient

/**
 * The user's music-warehouse: its config (Settings → Listening history), the [api] every
 * warehouse feature calls, and [state] for showing or hiding those features.
 *
 * [defaults] are debug-build defaults from local.properties (BuildConfig.WAREHOUSE_*); they apply
 * until the user saves or clears a config in Settings.
 */
class WarehouseServices(
    private val store: WarehouseConfigStore,
    private val http: OkHttpClient,
    private val defaults: WarehouseConfig? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    pendingSignIn: PendingSignInStore = InMemoryPendingSignInStore(),
    clock: () -> Long = System::currentTimeMillis,
) {
    private val _config = MutableStateFlow(current())
    /** Never shown in full: the token stays in here and in the Authorization header. */
    val config: StateFlow<WarehouseConfig?> = _config.asStateFlow()

    private val _state = MutableStateFlow(if (_config.value == null) WarehouseState.NotConfigured else WarehouseState.Checking)
    val state: StateFlow<WarehouseState> = _state.asStateFlow()

    private val _missing = MutableStateFlow<Set<WarehouseFeature>>(emptySet())
    /**
     * Features this deployment doesn't serve (it predates them): learned by [check] and from any
     * 404 on their routes. Hide what's built on them; [supports] combines this with [state].
     */
    val missing: StateFlow<Set<WarehouseFeature>> = _missing.asStateFlow()

    private val client = WarehouseApi(http, { _config.value }, io, sleep, ::observe)
    /** Throws [WarehouseException] with [Problem.NotConfigured] (sending nothing) until a config is set. */
    val api: Warehouse = client

    /** Lyrics through the warehouse's Spicy Lyrics proxy. Use it only while [supports] says [WarehouseFeature.Lyrics]. */
    val lyrics: LyricsEndpoint = object : LyricsEndpoint {
        override val via = LyricsEndpoint.Via.Warehouse
        override suspend fun fetch(trackId: String) = client.lyrics(trackId)
    }

    /** Whether to show a feature built on [feature]'s route now. Not observable: collect [state] and [missing]. */
    fun supports(feature: WarehouseFeature) = state.value.isUsable && feature !in missing.value

    val isConfigured get() = _config.value != null

    private val signIn = WarehouseSignIn(http, pendingSignIn, io, clock)
    private val _signInStatus = MutableStateFlow(if (signIn.inProgress) "Waiting for the browser…" else null)
    /** What "Sign in with Spotify" is doing or how it ended, for Settings; null when there's nothing to say. */
    val signInStatus: StateFlow<String?> = _signInStatus.asStateFlow()

    /**
     * Starts "Sign in with Spotify" for the warehouse at [baseUrl] (as typed): the page to open in a
     * Custom Tab, or null (with [signInStatus] saying why) when the address isn't usable.
     */
    fun startSignIn(baseUrl: String, deviceName: String): String? {
        val base = WarehouseConfig.normalizeUrl(baseUrl) ?: run { _signInStatus.value = "Enter the warehouse's https:// address first."; return null }
        _signInStatus.value = "Waiting for the browser…"
        return signIn.start(base, deviceName).toString()
    }

    /** The browser couldn't be opened, so nothing will come back. */
    fun abandonSignIn(message: String) {
        signIn.cancel()
        _signInStatus.value = message
    }

    /**
     * Finishes sign-in with the warehouse's [redirect] (`app.remastered://warehouse-auth?…`): saves
     * the read token it mints, exactly like one typed in, then [check]s it. The result's message
     * (success or not) can be shown as is.
     */
    suspend fun finishSignIn(redirect: String): Result<String> {
        _signInStatus.value = "Signing in…"
        val result = signIn.complete(redirect).map { cfg ->
            save(cfg)
            check()
            "Signed in. This phone has its own read token for 90 days."
        }
        _signInStatus.value = result.fold({ it }, { it.message ?: "Sign-in failed." })
        return result
    }

    fun save(config: WarehouseConfig) {
        store.save(config)
        _config.value = config
        _missing.value = emptySet()
        _state.value = WarehouseState.Checking
    }

    fun clear() {
        store.clear()
        signIn.cancel()
        _signInStatus.value = null
        _config.value = null
        _missing.value = emptySet()
        _state.value = WarehouseState.NotConfigured
    }

    /**
     * Asks for the latest play to confirm the saved config, then which [WarehouseFeature]s the
     * deployment serves (no database reads for most). [state] and [missing] follow the results.
     */
    suspend fun check(): Result<Play?> {
        if (_config.value == null) return Result.failure(WarehouseException(Problem.NotConfigured, "Listening history isn't set up"))
        _state.value = WarehouseState.Checking
        val latest = runCatching { api.plays(limit = 1).firstOrNull() }.onFailure { if (it !is WarehouseException) throw it }
        if (latest.isSuccess) WarehouseFeature.entries.forEach { client.serves(it) }
        return latest
    }

    /** [check] for [config] without saving it, so Settings can test what was typed. Doesn't touch [state]. */
    suspend fun test(config: WarehouseConfig): Result<Play?> {
        if (config == _config.value) return check()
        val probe = WarehouseApi(http, { config }, io, sleep)
        return runCatching { probe.plays(limit = 1).firstOrNull() }.onFailure { if (it !is WarehouseException) throw it }
    }

    private fun current(): WarehouseConfig? = store.load() ?: defaults?.takeIf { !store.touched }

    /**
     * Every [api] call reports here. Any answer past the token check (the warehouse checks it
     * first) means Ready; a rejected token or no answer says so. A server error only counts
     * while checking: otherwise one broken stat would hide every feature.
     */
    private fun observe(path: String, e: WarehouseException?) {
        if (_config.value == null) return
        WarehouseFeature.entries.firstOrNull { it.path == path }?.let { feature ->
            // Only a 404 says the route is missing; a 400 or any 2xx says it's there.
            when (e?.problem) {
                Problem.NotFound -> _missing.update { it + feature }
                null, Problem.BadRequest, Problem.Forbidden -> _missing.update { it - feature }
                else -> Unit
            }
        }
        _state.value = when (e?.problem) {
            null, Problem.Forbidden, Problem.NotFound, Problem.BadRequest -> WarehouseState.Ready
            Problem.Unauthorized -> WarehouseState.Unauthorized
            Problem.Unreachable -> WarehouseState.Unreachable(e.userMessage)
            Problem.ServerError, Problem.Busy, Problem.BadResponse ->
                if (_state.value == WarehouseState.Checking) WarehouseState.Unreachable(e.userMessage) else return
            Problem.NotConfigured -> return
        }
    }

    companion object {
        /** Debug-build defaults, or null when local.properties had none (always null in release). */
        fun defaults(url: String, token: String): WarehouseConfig? {
            val base = WarehouseConfig.normalizeUrl(url) ?: return null
            return token.trim().takeIf { it.isNotEmpty() }?.let { WarehouseConfig(base, it) }
        }
    }
}
