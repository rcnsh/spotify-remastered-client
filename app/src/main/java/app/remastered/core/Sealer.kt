package app.remastered.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Seals secrets (tokens) before they go into SharedPreferences. */
interface Sealer {
    fun seal(plain: String): String
    /** The plain text, or null when [sealed] can't be opened (the key is gone, or it was altered). */
    fun open(sealed: String): String?
}

/**
 * AES-GCM with a key under [alias] that never leaves the Android Keystore, so a copy of the
 * preferences file alone doesn't reveal what's in it. If the key is lost (restore to a new phone,
 * keystore reset) [open] returns null and the user signs in again. Sealed text is
 * `base64(iv):base64(ciphertext)`.
 */
class KeystoreSealer(private val alias: String) : Sealer {
    override fun seal(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return b64(cipher.iv) + ":" + b64(sealed)
    }

    override fun open(sealed: String): String? = try {
        val (iv, data) = sealed.split(':', limit = 2).map { Base64.decode(it, Base64.NO_WRAP) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        String(cipher.doFinal(data), Charsets.UTF_8)
    } catch (e: Exception) {
        // Exception class only: never the stored value.
        Log.w("Sealer", "A saved token can't be read (${e.javaClass.simpleName}); sign in again")
        null
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
