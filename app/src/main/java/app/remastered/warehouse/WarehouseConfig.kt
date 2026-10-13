package app.remastered.warehouse

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Where the warehouse is and the bearer token for its `/api` routes (READ_TOKEN or ADMIN_TOKEN). */
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
 * the token is AES-GCM encrypted with a key that never leaves the Android Keystore, so a copy of
 * the file alone doesn't reveal it. If the key is lost (restore to a new phone, keystore reset)
 * the token reads as missing and the user enters it again.
 */
class KeystoreWarehouseConfigStore(context: Context) : WarehouseConfigStore {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override val touched get() = prefs.getBoolean(TOUCHED, false)

    override fun load(): WarehouseConfig? {
        val url = prefs.getString(URL, null) ?: return null
        val token = prefs.getString(TOKEN, null)?.let(::decrypt) ?: return null
        return WarehouseConfig(url, token)
    }

    override fun save(config: WarehouseConfig) {
        prefs.edit().putString(URL, config.baseUrl).putString(TOKEN, encrypt(config.token)).putBoolean(TOUCHED, true).apply()
    }

    override fun clear() {
        prefs.edit().remove(URL).remove(TOKEN).putBoolean(TOUCHED, true).apply()
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    /** `base64(iv):base64(ciphertext)`. */
    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return b64(cipher.iv) + ":" + b64(sealed)
    }

    private fun decrypt(stored: String): String? = try {
        val (iv, sealed) = stored.split(':', limit = 2).map { Base64.decode(it, Base64.NO_WRAP) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        String(cipher.doFinal(sealed), Charsets.UTF_8)
    } catch (e: Exception) {
        // Exception class only: never the stored value.
        Log.w("Warehouse", "Saved warehouse token can't be read (${e.javaClass.simpleName}); enter it again in Settings")
        null
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private companion object {
        const val FILE = "warehouse"
        const val URL = "base_url"
        const val TOKEN = "token"
        const val TOUCHED = "touched"
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "remastered_warehouse_token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
