package com.smartcart.data.address

import com.smartcart.data.ApiJson
import com.smartcart.model.Category
import com.smartcart.model.Objective
import com.smartcart.model.ProviderKey
import com.smartcart.model.QuoteRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressBookTest {
    private var user: String? = "priya.k"
    private val store = InMemoryAddressStore()
    private val book = AddressBook(store) { user }

    private fun home(id: String = "", label: String = "Home", default: Boolean = false) =
        SavedAddress(id, label, "Flat 4B", "12 MG Road", "near the park", "Bengaluru", "560001", default)

    @Test fun validation() {
        val p = AddressRules.problems(home().copy(label = " ", pinCode = "56001", city = "x".repeat(41)))
        assertEquals(AddressProblem.REQUIRED, p[AddressField.LABEL])
        assertEquals(AddressProblem.INVALID_PIN, p[AddressField.PIN_CODE])
        assertEquals(AddressProblem.TOO_LONG, p[AddressField.CITY])
        assertEquals(AddressProblem.INVALID_PIN, AddressRules.problems(home().copy(pinCode = "012345"))[AddressField.PIN_CODE])
        assertTrue(AddressRules.problems(home().copy(landmark = null)).isEmpty())
    }

    @Test fun theFirstAddressIsTheDefaultAndExactlyOneStaysDefault() {
        book.save(home())
        book.save(home(label = "Work"))
        assertEquals(listOf("Home"), book.addresses.value.filter { it.isDefault }.map { it.label })
        book.save(book.byLabel("Work")!!.copy(isDefault = true))
        assertEquals(listOf("Work"), book.addresses.value.filter { it.isDefault }.map { it.label })
        book.delete(book.byLabel("Work")!!.id)
        assertEquals("Home", book.default!!.label)
    }

    @Test fun invalidAddressesAreNotSaved() {
        assertFalse(book.save(home().copy(pinCode = "abc")).isEmpty())
        assertTrue(book.addresses.value.isEmpty())
    }

    @Test fun labelsAreUniqueIgnoringCase() {
        book.save(home())
        assertEquals(AddressProblem.DUPLICATE, book.save(home(label = " home "))[AddressField.LABEL])
        val saved = book.default!!
        assertTrue("editing an address keeps its own label", book.save(saved.copy(city = "Mysuru")).isEmpty())
    }

    @Test fun addressesBelongToTheirUser() {
        book.save(home())
        user = "ravi.s"; book.refresh()
        assertTrue(book.addresses.value.isEmpty())
        user = "priya.k"; book.refresh()
        assertEquals(1, book.addresses.value.size)
        book.clearFor("priya.k")
        assertTrue(book.addresses.value.isEmpty())
    }

    @Test fun onlyTheLabelGoesIntoAComparisonRequest() {
        book.save(home())
        val a = book.default!!
        val body = ApiJson.lenient.encodeToString(
            QuoteRequest.serializer(),
            QuoteRequest(Category.GROCERY, eligibleProviders = listOf(ProviderKey.BLINKIT), objective = Objective.LOWEST_TOTAL, deliveryLabel = a.label),
        )
        assertTrue("\"delivery_label\":\"Home\"" in body)
        listOf(a.flatHouse, a.streetArea, a.city, a.pinCode, "near the park").forEach { assertFalse("$it must stay on the phone", it in body) }
    }
}
