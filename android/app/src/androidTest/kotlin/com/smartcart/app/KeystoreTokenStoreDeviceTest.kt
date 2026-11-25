package com.smartcart.app

import android.content.Context
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.smartcart.app.security.KeystoreTokenStore
import com.smartcart.data.StoredSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android Keystore behaviour; cannot be exercised under Robolectric. */
@RunWith(AndroidJUnit4::class)
class KeystoreTokenStoreDeviceTest {
    private val ctx = Device.context
    private val store = KeystoreTokenStore(ctx)
    private val prefs get() = ctx.getSharedPreferences("session", Context.MODE_PRIVATE)

    @Before fun setUp() = store.clear()
    @After fun tearDown() = store.clear()

    @Test
    fun savedSessionReadsBack() {
        store.save("priya.k", "refresh-token-123", remember = true)
        assertEquals(StoredSession("priya.k", "refresh-token-123", remembered = true), store.read())
    }

    @Test
    fun refreshTokenIsNeverStoredInPlaintext() {
        store.save("priya.k", "refresh-token-123", remember = true)
        val raw = prefs.all.values.joinToString("|") { it.toString() }
        assertFalse("token must be encrypted at rest", raw.contains("refresh-token-123"))
    }

    @Test
    fun tamperedCiphertextIsTreatedAsSignedOut() {
        store.save("priya.k", "refresh-token-123", remember = true)
        prefs.edit().putString("refresh_token", Base64.encodeToString(ByteArray(32) { 7 }, Base64.NO_WRAP)).commit()
        assertNull(store.read())
        assertNull("tampered session is wiped", prefs.getString("user_id", null))
    }

    @Test
    fun clearRemovesTheSession() {
        store.save("priya.k", "refresh-token-123", remember = false)
        store.clear()
        assertNull(store.read())
    }
}
