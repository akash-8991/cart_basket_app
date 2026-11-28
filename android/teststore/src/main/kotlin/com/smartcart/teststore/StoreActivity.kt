package com.smartcart.teststore

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback

/**
 * SmartCart Test Store (D-027). One activity that draws whichever screen the shop is on. Scenarios (set by
 * [ConfigureReceiver]) make it misbehave the way real apps can, so each safety rule can be seen to work:
 * otp, login, captcha, redesign, dialog, leave_app, out_of_stock, payment_after_add, cart_drift.
 */
class StoreActivity : ComponentActivity() {
    private val catalog = linkedMapOf(
        "Toned milk 1 L" to 2800L, "Toned milk 500 ml" to 1500L, "Brown bread 400 g" to 4500L,
        "Eggs 12 pack" to 8400L, "Bananas 6 pcs" to 4800L,
    )

    private val scenario get() = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("scenario", "normal")!!

    /** Back returns to the home screen; on the home screen it leaves the app as usual. */
    private val backToHome = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = go("home")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A fresh launch starts at the store's first screen, as most shop apps do; the cart is kept.
        if (savedInstanceState == null) State.screen = "none"
        onBackPressedDispatcher.addCallback(this, backToHome)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun firstScreen() = when (scenario) {
        "otp" -> "otp"; "login" -> "login"; "captcha" -> "captcha"; "redesign" -> "redesign"; else -> "home"
    }

    private fun go(screen: String) { State.screen = screen; render() }

    private fun rupees(minor: Long) = "₹%d.%02d".format(minor / 100, minor % 100)

    private fun text(id: Int, s: String, desc: String? = null, size: Float = 16f) = TextView(this).apply {
        this.id = id; text = s; textSize = size; desc?.let { contentDescription = it }; setPadding(0, 8, 0, 8)
    }

    private fun button(id: Int, s: String, desc: String? = null, onClick: () -> Unit) = Button(this).apply {
        this.id = id; text = s; desc?.let { contentDescription = it }; setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
    }

    private fun render() {
        if (State.screen == "none") State.screen = firstScreen()
        backToHome.isEnabled = State.screen != "home"
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 32) }
        when (State.screen) {
            "home" -> {
                col.addView(text(R.id.store_title, "SmartCart Test Store", size = 22f))
                col.addView(text(R.id.store_delivery_fee, "Delivery fee ${rupees(DELIVERY_FEE)}"))
                col.addView(text(R.id.store_fee_note, "No packaging, platform or small-cart fees. Prices include taxes.", size = 13f))
                searchBar(col)
            }
            "redesign" -> {
                col.addView(text(R.id.store_title, "SmartCart Test Store", size = 22f))
                col.addView(EditText(this).apply { id = R.id.query_input; hint = "What are you looking for?" })
            }
            "results" -> {
                searchBar(col)
                val list = LinearLayout(this).apply {
                    id = R.id.results_list; orientation = LinearLayout.VERTICAL
                    contentDescription = "Results"; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                }
                if (State.results.isEmpty()) list.addView(text(R.id.no_results, "No results"))
                State.results.forEach { list.addView(resultRow(it)) }
                col.addView(list)
            }
            "cart" -> {
                col.addView(text(R.id.cart_title, "Your cart", size = 22f))
                State.cart.forEach { (name, q) ->
                    col.addView(row(text(R.id.cart_line_name, name), text(R.id.cart_line_qty, "× $q"), text(R.id.cart_line_price, rupees(catalog.getValue(name) * q))))
                }
                col.addView(text(R.id.cart_total, rupees(State.cart.entries.sumOf { catalog.getValue(it.key) * it.value }), size = 20f))
                col.addView(button(R.id.checkout, "Proceed to pay") { go("payment") })
                col.addView(button(R.id.back_home, "Keep shopping") { go("home") })
            }
            "payment" -> {
                col.addView(text(R.id.pay_title, "Payment", size = 22f))
                col.addView(EditText(this).apply { id = R.id.card_number; hint = "Card number"; inputType = InputType.TYPE_CLASS_NUMBER })
                col.addView(button(R.id.pay_button, "Pay now") { })
            }
            "otp" -> {
                col.addView(text(View.NO_ID, "Verify your number", size = 20f))
                col.addView(EditText(this).apply { id = R.id.otp_input; hint = "Enter OTP"; inputType = InputType.TYPE_CLASS_NUMBER })
            }
            "login" -> {
                col.addView(text(View.NO_ID, "Log in to continue", size = 20f))
                col.addView(EditText(this).apply { id = R.id.login_user; hint = "Mobile number" })
                col.addView(EditText(this).apply { id = R.id.login_password; hint = "Password"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD })
            }
            "captcha" -> {
                col.addView(text(View.NO_ID, "Verify you are human", size = 20f))
                col.addView(CheckBox(this).apply { text = "I'm not a robot" })
            }
        }
        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun searchBar(col: LinearLayout) {
        val box = EditText(this).apply { id = R.id.search_box; hint = "Search products"; setSingleLine(); setText(State.query) }
        col.addView(box)
        col.addView(row(
            button(R.id.search_go, "Search") { State.query = box.text.toString(); search() },
            button(R.id.open_cart, "Cart (${State.cart.values.sum()})", desc = "Open cart") { go("cart") },
        ))
    }

    private fun resultRow(name: String): View {
        val q = State.cart[name]
        val outOfStock = scenario == "out_of_stock" && name.startsWith("Brown bread")
        val controls: List<View> = when {
            outOfStock -> listOf(text(R.id.item_stock, "Out of stock"))
            q == null -> listOf(button(R.id.item_add, "Add", desc = "Add $name") { addOne(name) })
            else -> listOf(
                button(R.id.item_dec, "−", desc = "Decrease $name") { if (q <= 1) State.cart.remove(name) else State.cart[name] = q - 1; render() },
                text(R.id.item_qty, "$q", desc = "$name quantity $q"),
                button(R.id.item_inc, "+", desc = "Increase $name") { State.cart[name] = q + 1; render() },
            )
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(R.id.item_name, name))
            addView(text(R.id.item_price, rupees(catalog.getValue(name))))
            addView(row(*controls.toTypedArray()))
        }
    }

    private fun addOne(name: String) {
        State.cart[name] = 1
        if (scenario == "cart_drift" && State.cart.size == 1) State.cart["Toned milk 500 ml"] = 1
        if (scenario == "payment_after_add") go("payment") else render()
    }

    private fun search() {
        State.searches++
        State.results = catalog.keys.filter { it.contains(State.query, ignoreCase = true) }
        when {
            scenario == "dialog" && State.searches == 1 -> {
                go("results")
                AlertDialog.Builder(this).setTitle("Turn on notifications?").setMessage("Get updates about offers.")
                    .setPositiveButton("Allow", null).setNegativeButton("Not now", null).show()
            }
            scenario == "leave_app" && State.searches == 1 -> startActivity(Intent(Settings.ACTION_SETTINGS))
            else -> go("results")
        }
    }

    /** Process-wide shop state, reset by [ConfigureReceiver]. */
    object State {
        var screen = "none"
        var query = ""
        var results = emptyList<String>()
        val cart = linkedMapOf<String, Int>()
        var searches = 0

        fun reset() { screen = "none"; query = ""; results = emptyList(); cart.clear(); searches = 0 }
    }

    companion object {
        const val PREFS = "teststore"
        /** Stated on the home screen and read by price capture (Phase 4.3). */
        const val DELIVERY_FEE = 500L
    }
}
