package com.smartcart.app.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.smartcart.data.StoredSession
import com.smartcart.data.TokenStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the rotating refresh token encrypted with an AES-GCM key held in the Android Keystore.
 * The key never leaves secure hardware where available and is destroyed on uninstall, so a
 * reinstall always requires a fresh sign-in (PRD v1.4 §11). Passwords are never stored.
 */
class KeystoreTokenStore(context: Context) : TokenStore {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    override fun save(userId: String, refreshToken: String, remember: Boolean) {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ct = cipher.doFinal(refreshToken.toByteArray())
        prefs.edit()
            .putString(K_USER, userId)
            .putString(K_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(K_TOKEN, Base64.encodeToString(ct, Base64.NO_WRAP))
            .putBoolean(K_REMEMBER, remember)
            .apply()
    }

    override fun read(): StoredSession? = runCatching {
        val user = prefs.getString(K_USER, null) ?: return null
        val iv = Base64.decode(prefs.getString(K_IV, null) ?: return null, Base64.NO_WRAP)
        val ct = Base64.decode(prefs.getString(K_TOKEN, null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        StoredSession(user, String(cipher.doFinal(ct)), prefs.getBoolean(K_REMEMBER, false))
    }.getOrElse {
        // Key invalidated or data tampered with: treat as signed out.
        clear()
        null
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "smartcart.session.v1"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val K_USER = "user_id"
        const val K_IV = "iv"
        const val K_TOKEN = "refresh_token"
        const val K_REMEMBER = "remember"
    }
}

/** Used in JVM tests and when the Keystore is unavailable. Holds tokens in memory only. */
class InMemoryTokenStore : TokenStore {
    private var s: StoredSession? = null
    override fun save(userId: String, refreshToken: String, remember: Boolean) { s = StoredSession(userId, refreshToken, remember) }
    override fun read(): StoredSession? = s
    override fun clear() { s = null }
}
