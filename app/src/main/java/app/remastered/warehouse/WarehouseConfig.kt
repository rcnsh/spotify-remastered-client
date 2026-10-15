package app.remastered.warehouse

import android.content.Context
import app.remastered.core.KeystoreSealer
import app.remastered.core.Sealer
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Where the warehouse is and the bearer token for its `/api` routes (a read token on this phone). */
data class WarehouseConfig(val baseUrl: String, val token: String) {
    override fun toString() = "WarehouseConfig(baseUrl=$baseUrl, token=<redacted>)"

    companion object {
        /**
         * The base URL as typed ("music-api.example.com", with or without https:// or a trailing
         * slash), normalized to `https://host/`. Null when it isn't a URL, or is plain http to
         * anything but this device or the emulator's host (the token would cross the network in
         * the clear).
         */
        fun normalizeUrl(input: String): String? {
            val trimmed = input.trim().ifEmpty { return null }
            val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
            val url = withScheme.toHttpUrlOrNull() ?: return null
            if (!url.isHttps && url.host !in LOCAL_HOSTS) return null
            return url.newBuilder().query(null).fragment(null).build().toString().let { if (it.endsWith("/")) it else "$it/" }
        }

        private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2")
    }
}

interface WarehouseConfigStore {
    /** The saved config; null when never set or after [clear]. */
    fun load(): WarehouseConfig?
    fun save(config: WarehouseConfig)
    fun clear()
    /** True once the user saved or cleared a config here, so build-time defaults no longer apply. */
    val touched: Boolean
}

class InMemoryWarehouseConfigStore(private var config: WarehouseConfig? = null, touched: Boolean = config != null) : WarehouseConfigStore {
    @Volatile override var touched = touched
        private set
    @Synchronized override fun load() = config
    @Synchronized override fun save(config: WarehouseConfig) { this.config = config; touched = true }
    @Synchronized override fun clear() { config = null; touched = true }
}

/**
 * SharedPreferences file `warehouse` (excluded from backup in res/xml). The URL is stored as is;
 * the token is sealed by [sealer] (a Keystore key by default, see [KeystoreSealer]), so a copy of
 * the file alone doesn't reveal it. If the key is lost the token reads as missing and the user
 * enters it again.
 */
class KeystoreWarehouseConfigStore(
    context: Context,
    private val sealer: Sealer = KeystoreSealer(ALIAS),
) : WarehouseConfigStore {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override val touched get() = prefs.getBoolean(TOUCHED, false)

    override fun load(): WarehouseConfig? {
        val url = prefs.getString(URL, null) ?: return null
        val token = prefs.getString(TOKEN, null)?.let(sealer::open) ?: return null
        return WarehouseConfig(url, token)
    }

    override fun save(config: WarehouseConfig) {
        prefs.edit().putString(URL, config.baseUrl).putString(TOKEN, sealer.seal(config.token)).putBoolean(TOUCHED, true).apply()
    }

    override fun clear() {
        prefs.edit().remove(URL).remove(TOKEN).putBoolean(TOUCHED, true).apply()
    }

    private companion object {
        const val FILE = "warehouse"
        const val URL = "base_url"
        const val TOKEN = "token"
        const val TOUCHED = "touched"
        const val ALIAS = "remastered_warehouse_token"
    }
}
