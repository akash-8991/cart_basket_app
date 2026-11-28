package com.smartcart.app

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.smartcart.app.feature.chat.ChatScreen
import com.smartcart.app.feature.settings.AddressesScreen
import com.smartcart.app.security.InMemoryTokenStore
import com.smartcart.app.ui.common.LocalAppContainer
import com.smartcart.data.address.SavedAddress
import com.smartcart.data.mock.MockConfig
import com.smartcart.designsystem.SmartCartTheme
import com.smartcart.model.Category
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction

/** Generous, because a freshly booted emulator can take several seconds to draw the chat. */
private const val WAIT = 15_000L

/** CR-004 in the app: user-entered delivery addresses, kept on the phone. Shared: JVM and device. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h2400dp")
class Cr004UiTest {
    @get:Rule val rule = createComposeRule()
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private fun s(id: Int, vararg args: Any) = ctx.getString(id, *args)

    /** A container with priya.k signed in (addresses belong to the signed-in user). */
    private fun signedIn(remember: Boolean = false) = AppContainer(null, TestFixtures.source(), MockConfig(latencyMs = 0), InMemoryTokenStore()).also {
        runBlocking { it.auth.login("priya.k", "correct horse battery", remember) }
        it.addresses.refresh()
    }

    private fun show(c: AppContainer, content: @Composable () -> Unit) = rule.setContent {
        CompositionLocalProvider(LocalAppContainer provides c) { SmartCartTheme(darkTheme = false) { content() } }
    }

    private fun waitForText(text: String, substring: Boolean = false) =
        rule.waitUntil(WAIT) { rule.onAllNodesWithText(text, substring = substring, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    private fun gone(text: String) = rule.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
    private fun field(label: Int) = rule.onNode(hasSetTextAction() and hasText(s(label)))

    private fun address(label: String, default: Boolean = false) =
        SavedAddress("", label, "Flat 4B", "12 MG Road", null, "Bengaluru", "560001", default)

    private fun fillForm(label: String = "Home", pin: String = "560001") {
        field(R.string.addr_label).performTextInput(label)
        field(R.string.addr_flat).performTextInput("Flat 4B")
        field(R.string.addr_street).performTextInput("12 MG Road")
        field(R.string.addr_city).performTextInput("Bengaluru")
        field(R.string.addr_pin).performTextInput(pin)
    }

    private fun save() = rule.onNode(hasText(s(R.string.addr_save)) and hasClickAction()).performScrollTo().performClick()

    private fun assumptions(c: AppContainer, quoteId: String): List<String> = c.comparisons.cached(quoteId)!!.assumptions

    @Test fun theFormShowsFieldErrorsThenSavesTheFirstAddressAsDefault() {
        val c = signedIn()
        show(c) { AddressesScreen(onBack = {}) }
        rule.onNodeWithText(s(R.string.addr_empty_title)).assertExists()
        rule.onNode(hasText(s(R.string.addr_add)) and hasClickAction()).performClick()
        save()
        waitForText(s(R.string.addr_problem_required))
        assertEquals(4, rule.onAllNodesWithText(s(R.string.addr_problem_required), useUnmergedTree = true).fetchSemanticsNodes().size) // label, flat, street, city
        waitForText(s(R.string.addr_problem_pin))
        fillForm(pin = "56001")
        save()
        waitForText(s(R.string.addr_problem_pin))
        assertTrue(gone(s(R.string.addr_problem_required)))
        field(R.string.addr_pin).performTextReplacement("560001")
        save()
        waitForText("Flat 4B, 12 MG Road, Bengaluru 560001")
        rule.onNodeWithText(s(R.string.addr_default)).assertExists()
        assertEquals(listOf("Home"), c.addresses.addresses.value.map { it.label })
    }

    @Test fun labelsMustBeUnique() {
        val c = signedIn()
        c.addresses.save(address("Home"))
        show(c) { AddressesScreen(onBack = {}) }
        rule.onNode(hasText(s(R.string.addr_add)) and hasClickAction()).performScrollTo().performClick()
        fillForm(label = "home")
        save()
        waitForText(s(R.string.addr_problem_duplicate))
        assertEquals(1, c.addresses.addresses.value.size)
    }

    @Test fun deleteAllAsksFirstThenRemovesEveryAddress() {
        val c = signedIn()
        c.addresses.save(address("Home"))
        c.addresses.save(address("Work"))
        show(c) { AddressesScreen(onBack = {}) }
        waitForText("Work")
        rule.onNode(hasText(s(R.string.addr_delete_all)) and hasClickAction()).performScrollTo().performClick()
        waitForText(s(R.string.addr_delete_all_title))
        rule.onNodeWithText(s(R.string.addr_delete_all_confirm)).performClick()
        waitForText(s(R.string.addr_empty_title))
        assertTrue(c.addresses.addresses.value.isEmpty())
    }

    @Test fun editingCanMakeAnotherAddressTheDefaultAndDeleteOne() {
        val c = signedIn()
        c.addresses.save(address("Home"))
        c.addresses.save(address("Work"))
        show(c) { AddressesScreen(onBack = {}) }
        rule.onNode(hasContentDescription(s(R.string.addr_edit_cd, "Work"))).performClick()
        waitForText(s(R.string.addr_edit_title))
        rule.onNode(hasText(s(R.string.addr_make_default)) and isEnabled()).performClick()
        save()
        rule.waitUntil(WAIT) { c.addresses.default?.label == "Work" }
        rule.onNode(hasContentDescription(s(R.string.addr_edit_cd, "Home"))).performClick()
        rule.onNode(hasText(s(R.string.addr_delete)) and hasClickAction()).performScrollTo().performClick()
        rule.onNodeWithText(s(R.string.addr_delete_confirm)).performClick()
        rule.waitUntil(WAIT) { c.addresses.addresses.value.map { it.label } == listOf("Work") }
    }

    /** Opens grocery chat and builds the cart that ends with "Compare full cart". */
    private fun cartReadyToCompare(c: AppContainer, onQuote: (String) -> Unit) {
        runBlocking { c.account.setConsent("ai_processing", true) }
        show(c) { ChatScreen(Category.GROCERY, onBack = {}, onComparison = onQuote) }
        rule.onNode(hasSetTextAction()).performTextInput("Milk, brown bread, eggs and bananas")
        rule.waitUntil(WAIT) { rule.onAllNodes(hasContentDescription(s(R.string.chat_send)) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasContentDescription(s(R.string.chat_send))).performClick()
        waitForText("Toned milk 1 L")
        // The keyboard is still resizing the window here, and on a phone an injected tap can be cancelled by the
        // layout moving under it; invoking the chip's click action runs the same handler without that race.
        val milk = hasText("Toned milk 1 L") and hasClickAction()
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(milk)
        rule.onNode(milk).performSemanticsAction(SemanticsActions.OnClick)
        waitForText(s(R.string.chat_compare_cart))
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(s(R.string.chat_compare_cart)))
    }

    @Test fun compareWithNoAddressAsksForOneFirstThenCarriesOn() {
        val c = signedIn()
        var quoteId: String? = null
        cartReadyToCompare(c) { quoteId = it }
        rule.onNode(hasContentDescription(s(R.string.chat_deliver_none_cd))).assertExists()
        rule.onNode(hasText(s(R.string.chat_compare_cart)) and hasClickAction()).performClick()
        waitForText(s(R.string.addr_new_title))
        assertEquals("nothing is compared without an address", null, quoteId)
        fillForm()
        save()
        rule.waitUntil(WAIT) { quoteId != null }
        assertTrue(assumptions(c, quoteId!!).contains("Delivery to your saved address \"Home\""))
    }

    @Test fun thePickerChoosesWhichLabelIsSent() {
        val c = signedIn()
        c.addresses.save(address("Home"))
        c.addresses.save(address("Work"))
        var quoteId: String? = null
        cartReadyToCompare(c) { quoteId = it }
        rule.onNode(hasContentDescription(s(R.string.chat_deliver_cd, "Home"))).performClick()
        // The menu is a popup window; on a device it can attach a frame or two after the tap.
        rule.waitUntil(WAIT) { rule.onAllNodes(hasText("Work") and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasText("Work") and hasClickAction()).performClick()
        rule.onNode(hasContentDescription(s(R.string.chat_deliver_cd, "Work"))).assertExists()
        // Let the menu finish closing (its popup would take the tap) and bring Compare back into view.
        rule.waitUntil(WAIT) { gone(s(R.string.addr_add)) }
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(s(R.string.chat_compare_cart)))
        rule.onNode(hasText(s(R.string.chat_compare_cart)) and hasClickAction()).performClick()
        rule.waitUntil(WAIT) { quoteId != null }
        val a = assumptions(c, quoteId!!)
        assertTrue(a.toString(), a.contains("Delivery to your saved address \"Work\""))
        assertTrue("the street address stays on the phone", a.none { line -> "MG Road" in line || "560001" in line })
        val events = c.analytics.events.value.toString()
        listOf("Home", "Work", "MG Road", "560001", "Bengaluru").forEach { assertTrue("\"$it\" must not reach analytics", it !in events) }
    }

    @Test fun signingOutRemovesAddressesUnlessTheDeviceIsRemembered() {
        val forgetful = signedIn(remember = false)
        forgetful.addresses.save(address("Home"))
        runBlocking { forgetful.signOut(); forgetful.auth.login("priya.k", "correct horse battery", false) }
        forgetful.addresses.refresh()
        assertTrue(forgetful.addresses.addresses.value.isEmpty())

        val remembered = signedIn(remember = true)
        remembered.addresses.save(address("Home"))
        runBlocking { remembered.signOut(); remembered.auth.login("priya.k", "correct horse battery", true) }
        remembered.addresses.refresh()
        assertEquals(listOf("Home"), remembered.addresses.addresses.value.map { it.label })
    }

    @Test fun deletingTheAccountRemovesAddresses() {
        val c = signedIn(remember = true)
        c.addresses.save(address("Home"))
        runBlocking { c.signOutDeletedAccount(); c.auth.login("priya.k", "correct horse battery", true) }
        c.addresses.refresh()
        assertTrue(c.addresses.addresses.value.isEmpty())
    }
}
