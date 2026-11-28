package com.smartcart.data.device

import com.smartcart.automation.Adapter
import com.smartcart.automation.FeeReading
import com.smartcart.automation.PriceCapture
import com.smartcart.model.AdapterRef
import com.smartcart.model.CartTask
import com.smartcart.model.MatchedLine
import com.smartcart.model.Money
import com.smartcart.model.NotReadReason
import com.smartcart.model.ObservationOutcome
import com.smartcart.model.ObservedFees
import com.smartcart.model.ObservedOffer
import com.smartcart.model.PriceObservation
import com.smartcart.model.ProviderKey
import com.smartcart.model.TaskProgressReport

/**
 * CR-005: what the phone sends the server. Only raw readings go out: which product was found, its price and
 * stock, the fees the app states, and the task's state and new timeline events. Totals, ranks, labels and
 * cart verification are worked out by the server from these, never taken from the phone.
 *
 * The HTTP repositories that send them come with the server-side engine and tasks (slices 4.4–4.5).
 */
object DeviceReports {
    fun adapterRef(adapter: Adapter) = AdapterRef(adapter.key, adapter.version)

    /** What a price check read for one provider, keyed by the quote's line IDs. */
    fun observation(provider: ProviderKey, lines: List<MatchedLine>, result: PriceCapture.Result, adapter: Adapter, capturedAt: String, currency: String): PriceObservation =
        when (result) {
            is PriceCapture.Result.NotRead -> notRead(provider, result.code, adapter, capturedAt)
            is PriceCapture.Result.Read -> PriceObservation(
                provider = provider,
                capturedAt = capturedAt,
                outcome = ObservationOutcome.READ,
                adapter = adapterRef(adapter),
                offers = lines.map { line ->
                    val o = result.offers.firstOrNull { it.item == CapturedQuote.item(line) }
                    if (o == null || !o.found) ObservedOffer(line.lineId, found = false)
                    else ObservedOffer(line.lineId, found = true, name = o.name, unitPrice = o.unitPriceMinor?.let { Money(it, currency) }, inStock = o.inStock)
                },
                fees = result.fees?.let { fees(it, currency) },
            )
        }

    /** The phone couldn't read the provider at all (service off, no consent, or the check halted). */
    fun notRead(provider: ProviderKey, reason: NotReadReason, adapter: Adapter, capturedAt: String) =
        PriceObservation(provider, capturedAt, ObservationOutcome.NOT_READ, reason, adapterRef(adapter))

    private fun fees(f: FeeReading, currency: String): ObservedFees {
        fun m(minor: Long?) = minor?.let { Money(it, currency) }
        return ObservedFees(
            deliveryFee = m(f.deliveryFeeMinor), packagingFee = m(f.packagingFeeMinor), platformFee = m(f.platformFeeMinor),
            smallCartFee = m(f.smallCartFeeMinor), taxes = m(f.taxesMinor),
        )
    }

    /**
     * The next progress report for a task the phone runs: its state now and the events after
     * [lastReportedEventSeq] (for the first report, the last event the server's approval answer held). Null
     * while the task is in a state only the server or the person sets. Text is cut to the contract's 200
     * characters.
     */
    fun progress(task: CartTask, reportSeq: Int, lastReportedEventSeq: Int, adapter: Adapter): TaskProgressReport? {
        if (task.status !in TaskProgressReport.DEVICE_STATUSES) return null
        return TaskProgressReport(
            reportSeq = reportSeq,
            status = task.status,
            actionsUsed = task.actionsUsed,
            events = task.events.filter { it.seq > lastReportedEventSeq }.sortedBy { it.seq }.map { it.copy(description = it.description.take(TEXT_MAX)) },
            checkpoint = task.checkpoint?.let { it.copy(prompt = it.prompt.take(TEXT_MAX)) },
            needsUserAction = task.needsUserAction?.let { it.copy(message = it.message.take(TEXT_MAX)) },
            failure = task.failure?.let { it.copy(message = it.message.take(TEXT_MAX)) },
            cart = task.cart,
            adapter = adapterRef(adapter),
        )
    }

    private const val TEXT_MAX = 200
}
