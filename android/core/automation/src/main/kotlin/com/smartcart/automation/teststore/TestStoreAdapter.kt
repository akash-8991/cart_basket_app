package com.smartcart.automation.teststore

import com.smartcart.automation.Adapter
import com.smartcart.automation.AdapterContext
import com.smartcart.automation.CartLineReading
import com.smartcart.automation.CartReading
import com.smartcart.automation.ExecutableAction
import com.smartcart.automation.FeeReading
import com.smartcart.automation.OfferReading
import com.smartcart.automation.Hazard
import com.smartcart.automation.ItemRequest
import com.smartcart.automation.ScreenSnapshot
import com.smartcart.automation.ScreenSpec
import com.smartcart.automation.Selector
import com.smartcart.automation.parseRupeesMinor
import com.smartcart.model.ActionType
import com.smartcart.model.UserActionReason

/**
 * Adapter for SmartCart Test Store (`android/teststore`), the app this repository ships purely so the runtime
 * can be exercised end to end (D-027). It is the reference for real adapters: semantic selectors only, every
 * screen fingerprinted, the payment screen declared as a hazard, and the cart read back for verification.
 *
 * [trustedSigners] is the certificate the Test Store was built with — in debug builds, the same debug key
 * as SmartCart itself.
 */
class TestStoreAdapter(override val trustedSigners: Set<String>) : Adapter {
    override val key = "teststore"
    override val version = "1"
    override val appName = "Test Store"
    override val packageName = PACKAGE
    override val supported = setOf(ActionType.OPEN_APP, ActionType.SEARCH, ActionType.SELECT_ITEM, ActionType.VERIFY_CART)

    private fun id(name: String) = Selector.Id("$PACKAGE:id/$name")
    private fun idText(name: String, text: String) = Selector.IdWithText("$PACKAGE:id/$name", text)

    // Order matters: the first spec that matches names the screen.
    override val screens = listOf(
        ScreenSpec("payment", listOf(id("pay_title")), hazard = Hazard.PAYMENT),
        ScreenSpec("cart", listOf(id("cart_title"), id("cart_total"))),
        ScreenSpec("results", listOf(id("search_box"), id("results_list"))),
        ScreenSpec("home", listOf(id("search_box"), id("store_title"))),
    )

    override suspend fun perform(step: ExecutableAction, ctx: AdapterContext) {
        when (step.action.type) {
            ActionType.OPEN_APP -> {
                ctx.launch()
                readFees(ctx.awaitScreen("home"))?.let(ctx::feesRead)
            }
            // In a cart plan SEARCH only checks the search screen is there; in a price check it looks one item up.
            ActionType.SEARCH -> step.item?.let { lookUp(it, ctx) } ?: ctx.awaitScreen("home", "results")
            ActionType.SELECT_ITEM -> add(step.item!!, ctx)
            ActionType.VERIFY_CART -> {
                val s = ctx.awaitScreen("home", "results")
                ctx.tap(s, id("open_cart"))
                val cart = ctx.awaitScreen("cart")
                readCart(cart)?.let(ctx::cartRead)
            }
            else -> error("unsupported: ${step.action.type}") // refused in preflight
        }
    }

    /** Searches for [item] and returns the results screen once it shows that item or "No results". */
    private suspend fun search(item: ItemRequest, ctx: AdapterContext): ScreenSnapshot {
        var s = ctx.awaitScreen("home", "results")
        ctx.type(s, id("search_box"), item.name)
        s = ctx.awaitScreen("home", "results") { it.has(idText("search_box", item.name)) }
        ctx.tap(s, id("search_go"))
        return ctx.awaitScreen("results") { it.has(idText("item_name", item.name)) || it.has(id("no_results")) }
    }

    private suspend fun lookUp(item: ItemRequest, ctx: AdapterContext) {
        ctx.offerRead(readOffer(search(item, ctx), item))
    }

    /** The item's row on a results screen: its name, then its price, then either the Add control or "Out of stock". */
    fun readOffer(screen: ScreenSnapshot, item: ItemRequest): OfferReading {
        val nodes = screen.nodes
        val start = nodes.indexOfFirst { it.id == "$PACKAGE:id/item_name" && it.text.equals(item.name, ignoreCase = true) }
        if (start < 0) return OfferReading(item, found = false)
        val row = nodes.drop(start + 1).takeWhile { it.id != "$PACKAGE:id/item_name" }
        return OfferReading(
            item = item,
            found = true,
            name = nodes[start].text,
            unitPriceMinor = parseRupeesMinor(row.firstOrNull { it.id == "$PACKAGE:id/item_price" }?.text),
            inStock = row.none { it.id == "$PACKAGE:id/item_stock" },
        )
    }

    /**
     * The fees the store states on its home screen. It names a delivery fee and says there are no packaging,
     * platform or small-cart fees and that prices include taxes; without that line those fees stay unknown.
     */
    fun readFees(screen: ScreenSnapshot): FeeReading? {
        val delivery = screen.first(id("store_delivery_fee"))?.let { parseRupeesMinor(it.text) } ?: return null
        val noOthers = screen.has(id("store_fee_note"))
        val zero = if (noOthers) 0L else null
        return FeeReading(deliveryFeeMinor = delivery, packagingFeeMinor = zero, platformFeeMinor = zero, smallCartFeeMinor = zero, taxesMinor = zero)
    }

    private suspend fun add(item: ItemRequest, ctx: AdapterContext) {
        var s = search(item, ctx)
        val add = Selector.Desc("Add ${item.name}")
        if (!s.has(add)) ctx.needsUser(UserActionReason.ITEM_UNAVAILABLE, "${item.name} isn't available in $appName")
        ctx.tap(s, add)
        for (q in 1..item.quantity) {
            s = ctx.awaitScreen("results") { it.has(Selector.Desc("${item.name} quantity $q")) }
            if (q < item.quantity) ctx.tap(s, Selector.Desc("Increase ${item.name}"))
        }
    }

    override fun readCart(screen: ScreenSnapshot): CartReading? {
        val names = screen.find(id("cart_line_name"))
        val qtys = screen.find(id("cart_line_qty"))
        val prices = screen.find(id("cart_line_price"))
        if (names.size != qtys.size || names.size != prices.size) return null
        val lines = names.indices.map { i ->
            val q = Regex("\\d+").find(qtys[i].text.orEmpty())?.value?.toIntOrNull() ?: return null
            CartLineReading(names[i].text.orEmpty(), q, parseRupeesMinor(prices[i].text))
        }
        return CartReading(lines, parseRupeesMinor(screen.first(id("cart_total"))?.text))
    }

    companion object {
        const val PACKAGE = "com.smartcart.teststore"
    }
}
