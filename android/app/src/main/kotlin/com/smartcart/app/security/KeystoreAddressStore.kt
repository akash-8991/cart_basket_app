package com.smartcart.app.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.smartcart.data.address.AddressStore
import com.smartcart.data.address.SavedAddress
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Saved delivery addresses (CR-004), encrypted with their own AES-GCM key in the Android Keystore, one entry
 * per user. Nothing here is backed up or sent anywhere; uninstalling destroys the key. Unreadable or tampered
 * data is dropped rather than shown.
 */
class KeystoreAddressStore(context: Context) : AddressStore {
    private val prefs = context.getSharedPreferences("addresses", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(SavedAddress.serializer())

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

    /** The preference key doesn't reveal the user id either. */
    private fun slot(userId: String): String =
        "u." + MessageDigest.getInstance("SHA-256").digest(userId.toByteArray()).joinToString("") { "%02x".format(it) }

    override fun read(userId: String): List<SavedAddress> = runCatching {
        val stored = prefs.getString(slot(userId), null) ?: return emptyList()
        val (iv, ct) = stored.split(':').map { Base64.decode(it, Base64.NO_WRAP) }
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        json.decodeFromString(serializer, String(cipher.doFinal(ct)))
    }.getOrElse {
        clear(userId)
        emptyList()
    }

    override fun write(userId: String, addresses: List<SavedAddress>) {
        if (addresses.isEmpty()) return clear(userId)
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ct = cipher.doFinal(json.encodeToString(serializer, addresses).toByteArray())
        prefs.edit()
            .putString(slot(userId), Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP))
            .commit()
    }

    override fun clear(userId: String) {
        prefs.edit().remove(slot(userId)).commit()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "smartcart.addresses.v1"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
