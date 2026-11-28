package com.smartcart.automation

import com.smartcart.model.ActionRisk
import com.smartcart.model.ActionType
import com.smartcart.model.PlannedAction

/** What the platform gives the runtime: read the screen and act on nodes, nothing else. */
interface Driver {
    /** Starts [packageName]'s launcher activity. False if it can't be started. */
    suspend fun launch(packageName: String): Boolean
    /** The active window, or null while there is none (e.g. mid-transition). */
    fun snapshot(): ScreenSnapshot?
    fun click(node: UiNode): Boolean
    fun setText(node: UiNode, text: String): Boolean
    fun back(): Boolean
}

/** Installed packages and their signing certificates (SHA-256, lower-case hex). */
interface PackageVerifier {
    fun isInstalled(packageName: String): Boolean
    fun signingDigests(packageName: String): Set<String>
}

/** One item to put in the cart. */
data class ItemRequest(val name: String, val quantity: Int)

data class ExecutableAction(val action: PlannedAction, val item: ItemRequest? = null)

/** The approved plan plus what each step needs to know. Built by code from the comparison, never by a model. */
data class ExecutablePlan(val actions: List<ExecutableAction>) {
    val items: List<ItemRequest> get() = actions.mapNotNull { it.item }

    companion object {
        /** The grocery/food plan shape used by the fixtures: open, search, add each line, verify the cart. */
        fun forCart(appName: String, items: List<ItemRequest>): ExecutablePlan {
            var n = 0
            fun id() = "a${++n}"
            val actions = buildList {
                add(ExecutableAction(PlannedAction(id(), ActionType.OPEN_APP, "Open $appName", ActionRisk.LOW)))
                add(ExecutableAction(PlannedAction(id(), ActionType.SEARCH, "Search for each item", ActionRisk.LOW)))
                items.forEach { add(ExecutableAction(PlannedAction(id(), ActionType.SELECT_ITEM, "Add ${it.name} × ${it.quantity}", ActionRisk.MEDIUM), it)) }
                add(ExecutableAction(PlannedAction(id(), ActionType.VERIFY_CART, "Check the cart matches what you approved", ActionRisk.LOW)))
            }
            return ExecutablePlan(actions)
        }
    }
}

/** A line as read back from the partner app's cart. Prices in minor units (paise). */
data class CartLineReading(val name: String, val quantity: Int, val lineTotalMinor: Long?)
data class CartReading(val lines: List<CartLineReading>, val totalMinor: Long?, val currency: String = "INR")

/**
 * How to drive one partner app, written from measured facts (provider-matrix.md, spike Q1–Q3): its package,
 * trusted signing certificates, the screens it has and how to perform each kind of action. Adapters only
 * act through [AdapterContext], which applies every safety rule before and after each step.
 */
interface Adapter {
    val key: String
    val version: String
    val appName: String
    val packageName: String
    val trustedSigners: Set<String>
    val screens: List<ScreenSpec>
    val supported: Set<ActionType>

    suspend fun perform(step: ExecutableAction, ctx: AdapterContext)
    /** Reads the cart screen; null if it can't be read with confidence. */
    fun readCart(screen: ScreenSnapshot): CartReading?
}

/** The only way an adapter touches the phone. Every call may end the task by throwing [Halted]. */
interface AdapterContext {
    suspend fun launch()
    /** Waits until one of [names] is on screen (or [condition] holds on a known screen) and returns it. */
    suspend fun awaitScreen(vararg names: String, condition: (ScreenSnapshot) -> Boolean = { true }): ScreenSnapshot
    suspend fun tap(screen: ScreenSnapshot, selector: Selector)
    suspend fun type(screen: ScreenSnapshot, selector: Selector, text: String)
    suspend fun back()
    /** The step can't continue without the person (e.g. an item is out of stock). */
    fun needsUser(reason: com.smartcart.model.UserActionReason, message: String): Nothing
    /** Records what the cart screen shows, for verification. */
    fun cartRead(reading: CartReading)
}

/** Parses "₹1,234.50" / "Rs 54" / "54.00" into paise. */
fun parseRupeesMinor(text: String?): Long? {
    val m = Regex("""(\d[\d,]*)(?:\.(\d{1,2}))?""").find(text ?: return null) ?: return null
    val whole = m.groupValues[1].replace(",", "").toLongOrNull() ?: return null
    val frac = m.groupValues[2].padEnd(2, '0').ifEmpty { "00" }.toLong()
    return whole * 100 + frac
}
