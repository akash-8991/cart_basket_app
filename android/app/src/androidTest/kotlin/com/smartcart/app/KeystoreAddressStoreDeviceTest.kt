package com.smartcart.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.smartcart.app.security.KeystoreAddressStore
import com.smartcart.data.address.SavedAddress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** CR-004: saved addresses are encrypted at rest with a Keystore key; real Keystore only, so device-only. */
@RunWith(AndroidJUnit4::class)
class KeystoreAddressStoreDeviceTest {
    private val ctx = Device.context
    private val store = KeystoreAddressStore(ctx)
    private val prefs get() = ctx.getSharedPreferences("addresses", Context.MODE_PRIVATE)
    private val home = SavedAddress("a1", "Home", "Flat 4B, Lake View", "12 MG Road", "near the park", "Bengaluru", "560001", isDefault = true)

    @Before fun setUp() { prefs.edit().clear().commit() }
    @After fun tearDown() { prefs.edit().clear().commit() }

    @Test fun addressesReadBackPerUser() {
        store.write("priya.k", listOf(home))
        assertEquals(listOf(home), store.read("priya.k"))
        assertTrue(store.read("ravi.s").isEmpty())
    }

    @Test fun nothingIsStoredInPlaintext() {
        store.write("priya.k", listOf(home))
        val raw = prefs.all.keys.joinToString("|") + "|" + prefs.all.values.joinToString("|") { it.toString() }
        listOf("Home", "Lake View", "MG Road", "near the park", "Bengaluru", "560001", "priya.k").forEach {
            assertFalse("\"$it\" must not be readable on disk", raw.contains(it))
        }
    }

    @Test fun tamperedDataIsDroppedNotShown() {
        store.write("priya.k", listOf(home))
        val key = prefs.all.keys.single()
        prefs.edit().putString(key, "AAAAAAAAAAAAAAAA:AAAAAAAAAAAAAAAAAAAAAAAAAAAA").commit()
        assertTrue(store.read("priya.k").isEmpty())
        assertTrue("tampered entry is wiped", prefs.all.isEmpty())
    }

    @Test fun clearRemovesOnlyThatUser() {
        store.write("priya.k", listOf(home))
        store.write("ravi.s", listOf(home.copy(id = "b1")))
        store.clear("priya.k")
        assertTrue(store.read("priya.k").isEmpty())
        assertEquals(1, store.read("ravi.s").size)
    }
}
