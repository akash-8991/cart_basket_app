package com.smartcart.automation

import com.smartcart.automation.teststore.TestStoreAdapter

/**
 * A JVM stand-in for the Test Store app: the same screens, IDs and descriptions, driven through [Driver].
 * The real app (android/teststore) is exercised by the device test; this one lets every hazard be tested
 * quickly and deterministically.
 */
class FakeStore(
    private val scenario: String = "normal",
    private val now: () -> Long = { 0L },
    /** Screen changes appear this long after the action that caused them. */
    private val latencyMs: Long = 0,
) : Driver {
    private val pkg = TestStoreAdapter.PACKAGE
    private val catalog = linkedMapOf("Toned milk 1 L" to 2800L, "Toned milk 500 ml" to 1500L, "Brown bread 400 g" to 4500L, "Eggs 12 pack" to 8400L, "Bananas 6 pcs" to 4800L)

    var screen = "none"; private set
    private var query = ""
    private var results = emptyList<String>()
    private val cart = linkedMapOf<String, Int>()
    private var searches = 0
    private var pending: Pair<Long, () -> Unit>? = null

    val clicks = mutableListOf<String>()
    var launches = 0; private set
    /** Set by [userSignsIn]: the person dealt with the login/OTP/CAPTCHA screen themselves. */
    private var cleared = false

    private fun later(change: () -> Unit) {
        if (latencyMs == 0L) change() else pending = (now() + latencyMs) to change
    }

    override suspend fun launch(packageName: String): Boolean {
        launches++
        later { screen = when { cleared -> "home"; scenario == "otp" -> "otp"; scenario == "login" -> "login"; scenario == "captcha" -> "captcha"; scenario == "redesign" -> "redesign"; else -> "home" } }
        return true
    }

    override fun snapshot(): ScreenSnapshot? {
        pending?.let { (at, change) -> if (now() >= at) { change(); pending = null } }
        return when (screen) {
            "none" -> null
            "settings" -> ScreenSnapshot("com.android.settings", UiNode(text = "Settings"))
            else -> ScreenSnapshot(pkg, UiNode(children = build()))
        }
    }

    private fun n(id: String? = null, text: String? = null, desc: String? = null, hint: String? = null, editable: Boolean = false, password: Boolean = false, onClick: (() -> Unit)? = null) =
        UiNode(id = id?.let { "$pkg:id/$it" }, text = text, desc = desc, hint = hint, clickable = onClick != null, editable = editable, password = password, handle = onClick)

    private fun rupees(minor: Long) = "₹%d.%02d".format(minor / 100, minor % 100)

    private fun searchBar() = listOf(
        n("search_box", text = query.ifEmpty { null }, hint = "Search products", editable = true),
        n("search_go", text = "Search", onClick = { search() }),
        n("open_cart", text = "Cart (${cart.values.sum()})", desc = "Open cart", onClick = { later { screen = "cart" } }),
    )

    private fun build(): List<UiNode> = when (screen) {
        "home" -> listOf(
            n("store_title", text = "SmartCart Test Store"),
            n("store_delivery_fee", text = "Delivery fee ${rupees(DELIVERY_FEE)}"),
            n("store_fee_note", text = "No packaging, platform or small-cart fees. Prices include taxes."),
        ) + searchBar()
        "redesign" -> listOf(n("store_title", text = "SmartCart Test Store"), n("query_input", hint = "What are you looking for?", editable = true))
        "results" -> searchBar() + n("results_list") + (if (results.isEmpty()) listOf(n("no_results", text = "No results")) else results.flatMap { row(it) })
        "dialog" -> listOf(UiNode(id = "android:id/alertTitle", text = "Turn on notifications?"), UiNode(id = "android:id/button1", text = "Allow", clickable = true))
        "cart" -> {
            val lines = cart.flatMap { (name, q) ->
                listOf(n("cart_line_name", text = name), n("cart_line_qty", text = "× $q"), n("cart_line_price", text = rupees(catalog.getValue(name) * q)))
            }
            listOf(n("cart_title", text = "Your cart")) + lines + listOf(
                n("cart_total", text = rupees(cart.entries.sumOf { catalog.getValue(it.key) * it.value })),
                n("checkout", text = "Proceed to pay", onClick = { later { screen = "payment" } }),
            )
        }
        "payment" -> listOf(n("pay_title", text = "Payment"), n("card_number", hint = "Card number", editable = true), n("pay_button", text = "Pay now", onClick = { }))
        "otp" -> listOf(n(text = "Verify your number"), n("otp_input", hint = "Enter OTP", editable = true))
        "login" -> listOf(n(text = "Log in to continue"), n("user", hint = "Mobile number", editable = true), n("pw", hint = "Password", editable = true, password = true))
        "captcha" -> listOf(n(text = "Verify you are human"), n(text = "I'm not a robot", onClick = { }))
        else -> emptyList()
    }

    private fun row(name: String): List<UiNode> {
        val q = cart[name]
        val outOfStock = scenario == "out_of_stock" && name.startsWith("Brown bread")
        return listOf(n("item_name", text = name), n("item_price", text = rupees(catalog.getValue(name)))) + when {
            outOfStock -> listOf(n("item_stock", text = "Out of stock"))
            q == null -> listOf(n("item_add", text = "Add", desc = "Add $name", onClick = { addOne(name) }))
            else -> listOf(
                n("item_dec", desc = "Decrease $name", onClick = { later { if (q <= 1) cart.remove(name) else cart[name] = q - 1 } }),
                n("item_qty", text = "$q", desc = "$name quantity $q"),
                n("item_inc", desc = "Increase $name", onClick = { later { cart[name] = q + 1 } }),
            )
        }
    }

    private fun addOne(name: String) = later {
        cart[name] = 1
        if (scenario == "payment_after_add") screen = "payment"
        if (scenario == "cart_drift" && cart.size == 1) cart["Toned milk 500 ml"] = 1
    }

    private fun search() = later {
        searches++
        results = catalog.keys.filter { it.contains(query, ignoreCase = true) }
        screen = when {
            scenario == "dialog" && searches == 1 -> "dialog"
            scenario == "leave_app" && searches == 1 -> "settings"
            else -> "results"
        }
    }

    override fun click(node: UiNode): Boolean {
        clicks += node.desc ?: node.text ?: node.id.orEmpty()
        @Suppress("UNCHECKED_CAST")
        (node.handle as? () -> Unit ?: return false).invoke()
        return true
    }

    override fun setText(node: UiNode, text: String): Boolean {
        if (node.id?.endsWith(":id/search_box") != true) return false
        query = text
        return true
    }

    /** What the person does when handed the phone: signs in, enters the OTP or solves the CAPTCHA themselves. */
    fun userSignsIn() { cleared = true; screen = "home" }

    /** What the person does with an unexpected dialog: dismisses it. */
    fun userDismissesDialog() { screen = "home" }

    override fun back(): Boolean {
        screen = "home"
        return true
    }
}

/** The delivery fee both Test Stores state (₹5.00). */
const val DELIVERY_FEE = 500L

class FakeVerifier(private val installed: Boolean = true, private val signers: Set<String> = setOf(GOOD_SIGNER)) : PackageVerifier {
    override fun isInstalled(packageName: String) = installed
    override fun signingDigests(packageName: String) = if (installed) signers else emptySet()

    companion object { const val GOOD_SIGNER = "ab12" }
}
