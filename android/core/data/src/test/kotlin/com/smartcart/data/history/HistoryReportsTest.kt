package com.smartcart.data.history

import com.smartcart.data.ApiJson
import com.smartcart.model.AdapterRef
import com.smartcart.model.Confidence
import com.smartcart.model.HistoryBatch
import com.smartcart.model.ImportField
import com.smartcart.model.ImportSession
import com.smartcart.model.ImportStatus
import com.smartcart.model.OrderStatus
import com.smartcart.model.PrivacyMode
import com.smartcart.model.ProviderImportStatus
import com.smartcart.model.ProviderKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** CR-007 / A-10: only the chosen fields of reviewed orders in a sync session ever leave the phone. */
class HistoryReportsTest {
    private val parsed = listOf(
        ParsedOrder("BLK-1001", "2026-11-20T03:10:00Z", OrderStatus.DELIVERED, 18200, listOf(ParsedItem("Toned milk 1 L", 2, 5600)), "Home", Confidence.HIGH),
    )
    private val adapter = AdapterRef("teststore", "1")
    private fun session(mode: PrivacyMode) = ImportSession("imp_1", ImportStatus.RUNNING, mode, emptyList(), dedupSalt = if (mode == PrivacyMode.NORMALIZED_UPLOAD) "salt123" else null)
    private val dateAndAmounts = setOf(ImportField.ORDER_DATE, ImportField.ITEMS, ImportField.AMOUNTS)

    @Test fun deviceOnlyAndUnreviewedSendCountsOnly() {
        val deviceOnly = HistoryReports.batch(1, session(PrivacyMode.DEVICE_ONLY), ImportField.entries.toSet(), ProviderKey.BLINKIT, ProviderImportStatus.COMPLETED, adapter, parsed, reviewed = true)
        assertTrue(deviceOnly.orders.isEmpty())
        assertEquals(1, deviceOnly.ordersFound)
        val unreviewed = HistoryReports.batch(1, session(PrivacyMode.NORMALIZED_UPLOAD), ImportField.entries.toSet(), ProviderKey.BLINKIT, ProviderImportStatus.COMPLETED, adapter, parsed, reviewed = false)
        assertTrue(unreviewed.orders.isEmpty())
    }

    @Test fun onlyTheChosenFieldsAndNoProviderOrderId() {
        val b = HistoryReports.batch(1, session(PrivacyMode.NORMALIZED_UPLOAD), dateAndAmounts, ProviderKey.BLINKIT, ProviderImportStatus.COMPLETED, adapter, parsed, reviewed = true)
        val o = b.orders.single()
        assertEquals("2026-11-20T03:10:00Z", o.orderedAt)
        assertEquals(18200L, o.total!!.amountMinor)
        assertNull("status wasn't chosen", o.status)
        assertNull("location wasn't chosen", o.locationLabel)
        assertNull("quantity wasn't chosen", o.items!!.single().quantity)
        assertEquals(5600L, o.items!!.single().amount!!.amountMinor)
        assertTrue(Regex("^[0-9a-f]{64}$").matches(o.sourceRef))
        val wire = ApiJson.strict.encodeToString(HistoryBatch.serializer(), b)
        assertFalse("the provider's order ID never leaves the phone", "BLK-1001" in wire)
        assertFalse("Home" in wire)
        assertEquals(b, ApiJson.strict.decodeFromString(HistoryBatch.serializer(), wire))
        assertEquals("the same order hashes the same", o.sourceRef, HistoryReports.sourceRef("salt123", ProviderKey.BLINKIT, "BLK-1001"))
    }
}
