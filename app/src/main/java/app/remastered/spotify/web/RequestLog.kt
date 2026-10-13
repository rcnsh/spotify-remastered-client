package app.remastered.spotify.web

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * The last [max] HTTP requests, for Settings → Developer. Records method, host, path, query,
 * status and timing only: never headers (the bearer token) or bodies (token responses).
 */
class RequestLog(private val max: Int = 200, private val logcat: () -> Boolean = { false }) : Interceptor {
    data class Entry(
        val atEpochMs: Long,
        val method: String,
        val host: String,
        val path: String,
        val query: String?,
        /** HTTP status, or null when the request failed before a response. */
        val status: Int?,
        val tookMs: Long,
        val error: String? = null,
    ) {
        val ok get() = status != null && status in 200..299
    }

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    /** Newest first. */
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun clear() { _entries.value = emptyList() }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        // The token endpoint's query is empty; Web API queries carry ids and search terms only.
        fun entry(status: Int?, took: Long, error: String? = null) = Entry(
            System.currentTimeMillis(), request.method, url.host, url.encodedPath, url.encodedQuery, status, took, error,
        )
        val start = System.nanoTime()
        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            add(entry(null, (System.nanoTime() - start) / 1_000_000, e.javaClass.simpleName))
            throw e
        }
        add(entry(response.code, (System.nanoTime() - start) / 1_000_000))
        return response
    }

    private fun add(e: Entry) {
        _entries.update { (listOf(e) + it).take(max) }
        if (logcat()) Log.d(TAG, "${e.method} ${e.host}${e.path} → ${e.status ?: e.error} (${e.tookMs} ms)")
    }

    private companion object { const val TAG = "WebApi" }
}
