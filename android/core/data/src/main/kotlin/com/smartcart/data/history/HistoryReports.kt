package com.smartcart.data.history

import com.smartcart.model.AdapterRef
import com.smartcart.model.Confidence
import com.smartcart.model.HistoryBatch
import com.smartcart.model.ImportFailureReason
import com.smartcart.model.ImportField
import com.smartcart.model.ImportOutcome
import com.smartcart.model.ImportSession
import com.smartcart.model.Money
import com.smartcart.model.OrderStatus
import com.smartcart.model.PrivacyMode
import com.smartcart.model.ProviderImportStatus
import com.smartcart.model.ProviderKey
import com.smartcart.model.UploadedItem
import com.smartcart.model.UploadedOrder
import java.security.MessageDigest

/** One order as the phone read it. It stays on the phone; only [HistoryReports] decides what may leave. */
data class ParsedOrder(
    val providerOrderId: String,
    val orderedAt: String?,
    val status: OrderStatus?,
    val totalMinor: Long?,
    val items: List<ParsedItem>,
    val locationLabel: String?,
    val confidence: Confidence,
    val currency: String = "INR",
)

data class ParsedItem(val name: String, val quantity: Int?, val amountMinor: Long?)

/**
 * CR-007: what the phone sends about an import. Every batch carries the app's progress and counts; orders go
 * only in a sync session the person has reviewed, and each order carries only the fields they chose. The
 * provider's order ID is replaced by a salted hash, so duplicates can be found without it leaving the phone.
 */
object HistoryReports {
    fun sourceRef(salt: String, provider: ProviderKey, providerOrderId: String): String =
        MessageDigest.getInstance("SHA-256").digest("$salt${provider.key}:$providerOrderId".toByteArray()).joinToString("") { "%02x".format(it) }

    /** [p] cut down to the [fields] the person chose (A-10): anything else is left out. */
    fun order(salt: String, provider: ProviderKey, p: ParsedOrder, fields: Set<ImportField>): UploadedOrder {
        fun m(minor: Long?) = minor?.let { Money(it, p.currency) }
        val items = if (ImportField.ITEMS in fields) p.items.map { i ->
            UploadedItem(
                name = i.name.take(120),
                quantity = i.quantity?.takeIf { ImportField.QUANTITY in fields },
                amount = m(i.amountMinor)?.takeIf { ImportField.AMOUNTS in fields },
            )
        } else null
        return UploadedOrder(
            sourceRef = sourceRef(salt, provider, p.providerOrderId),
            orderedAt = p.orderedAt?.takeIf { ImportField.ORDER_DATE in fields },
            status = p.status?.takeIf { ImportField.STATUS in fields },
            total = m(p.totalMinor)?.takeIf { ImportField.AMOUNTS in fields },
            items = items,
            locationLabel = p.locationLabel?.take(40)?.takeIf { ImportField.LOCATION_LABEL in fields },
            extractionConfidence = p.confidence,
        )
    }

    /** One app's batch. [parsed] is only used when the session syncs and the person has [reviewed] the orders. */
    fun batch(
        seq: Int,
        session: ImportSession,
        fields: Set<ImportField>,
        provider: ProviderKey,
        status: ProviderImportStatus,
        adapter: AdapterRef,
        parsed: List<ParsedOrder>,
        reviewed: Boolean,
        confidence: Confidence? = null,
        failure: ImportFailureReason? = null,
        outcome: ImportOutcome? = null,
    ): HistoryBatch {
        val salt = session.dedupSalt
        val upload = session.uploadMode == PrivacyMode.NORMALIZED_UPLOAD && reviewed && salt != null
        return HistoryBatch(
            batchSeq = seq,
            provider = provider,
            status = status,
            ordersFound = parsed.size,
            confidence = confidence,
            failureReason = failure,
            adapter = adapter,
            reviewed = reviewed,
            orders = if (upload) parsed.map { order(salt!!, provider, it, fields) } else emptyList(),
            importOutcome = outcome,
        )
    }
}
