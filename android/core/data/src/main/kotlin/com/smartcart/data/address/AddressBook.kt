package com.smartcart.data.address

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * A delivery address the user typed in (CR-004). It lives only on this phone, encrypted; the server only
 * ever receives [label] when the user compares prices (D-025).
 */
@Serializable
data class SavedAddress(
    val id: String,
    val label: String,
    val flatHouse: String,
    val streetArea: String,
    val landmark: String? = null,
    val city: String,
    val pinCode: String,
    val isDefault: Boolean = false,
) {
    /** One line for lists: "Flat 4B, MG Road, near the park, Bengaluru 560001". */
    val oneLine: String get() = listOfNotNull(flatHouse, streetArea, landmark, city).joinToString(", ") + " " + pinCode
}

/** Per-user storage. Android: Keystore-encrypted (KeystoreAddressStore); tests: in memory. */
interface AddressStore {
    fun read(userId: String): List<SavedAddress>
    fun write(userId: String, addresses: List<SavedAddress>)
    fun clear(userId: String)
}

class InMemoryAddressStore : AddressStore {
    private val map = mutableMapOf<String, List<SavedAddress>>()
    override fun read(userId: String) = map[userId].orEmpty()
    override fun write(userId: String, addresses: List<SavedAddress>) { map[userId] = addresses }
    override fun clear(userId: String) { map.remove(userId) }
}

enum class AddressField { LABEL, FLAT_HOUSE, STREET_AREA, LANDMARK, CITY, PIN_CODE }
enum class AddressProblem { REQUIRED, TOO_LONG, INVALID_PIN, DUPLICATE }

object AddressRules {
    private val PIN = Regex("^[1-9][0-9]{5}$")
    const val LABEL_MAX = 40 // matches QuoteRequest.delivery_label (contract 1.3.0)

    fun problems(a: SavedAddress): Map<AddressField, AddressProblem> = buildMap {
        fun required(f: AddressField, v: String, max: Int) {
            when {
                v.isBlank() -> put(f, AddressProblem.REQUIRED)
                v.trim().length > max -> put(f, AddressProblem.TOO_LONG)
            }
        }
        required(AddressField.LABEL, a.label, LABEL_MAX)
        required(AddressField.FLAT_HOUSE, a.flatHouse, 80)
        required(AddressField.STREET_AREA, a.streetArea, 80)
        a.landmark?.takeIf { it.trim().length > 80 }?.let { put(AddressField.LANDMARK, AddressProblem.TOO_LONG) }
        required(AddressField.CITY, a.city, 40)
        if (!PIN.matches(a.pinCode.trim())) put(AddressField.PIN_CODE, AddressProblem.INVALID_PIN)
    }
}

/**
 * The signed-in user's saved addresses. Exactly one is the default whenever any exist; it's the one the cart
 * picks unless the user chooses another.
 */
class AddressBook(private val store: AddressStore, private val currentUser: () -> String?) {
    private val _addresses = MutableStateFlow<List<SavedAddress>>(emptyList())
    val addresses: StateFlow<List<SavedAddress>> = _addresses

    fun refresh() { _addresses.value = currentUser()?.let(store::read).orEmpty() }

    /** Adds or replaces [a] (a blank id means new). Returns the problems instead of saving, if any. */
    fun save(a: SavedAddress): Map<AddressField, AddressProblem> {
        val user = currentUser() ?: return emptyMap()
        val existing = store.read(user)
        val problems = AddressRules.problems(a).toMutableMap()
        // Only the label reaches the server and the cart picker chooses by label, so labels are unique.
        if (AddressField.LABEL !in problems && existing.any { it.id != a.id && it.label.equals(a.label.trim(), ignoreCase = true) }) {
            problems[AddressField.LABEL] = AddressProblem.DUPLICATE
        }
        if (problems.isNotEmpty()) return problems
        val clean = a.copy(
            id = a.id.ifBlank { UUID.randomUUID().toString() },
            label = a.label.trim(), flatHouse = a.flatHouse.trim(), streetArea = a.streetArea.trim(),
            landmark = a.landmark?.trim()?.ifBlank { null }, city = a.city.trim(), pinCode = a.pinCode.trim(),
        )
        var list = existing.filterNot { it.id == clean.id } + clean
        val defaultId = if (clean.isDefault || list.none { it.isDefault }) clean.id else list.first { it.isDefault }.id
        list = list.map { it.copy(isDefault = it.id == defaultId) }
        store.write(user, list)
        refresh()
        return emptyMap()
    }

    fun delete(id: String) {
        val user = currentUser() ?: return
        var list = store.read(user).filterNot { it.id == id }
        if (list.isNotEmpty() && list.none { it.isDefault }) list = list.mapIndexed { i, x -> x.copy(isDefault = i == 0) }
        store.write(user, list)
        refresh()
    }

    fun deleteAll() {
        currentUser()?.let(store::clear)
        refresh()
    }

    /** Removes [userId]'s addresses from this phone (sign-out of a non-remembered device, account deletion). */
    fun clearFor(userId: String) {
        store.clear(userId)
        refresh()
    }

    fun byLabel(label: String?): SavedAddress? = _addresses.value.firstOrNull { it.label == label }
    val default: SavedAddress? get() = _addresses.value.firstOrNull { it.isDefault }
}
