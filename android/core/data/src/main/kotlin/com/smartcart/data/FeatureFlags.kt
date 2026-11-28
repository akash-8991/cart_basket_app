package com.smartcart.data

import com.smartcart.model.Category
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Frozen flag names and defaults from contracts/feature-flags.yaml. Server-controlled from Phase 3. */
object FlagKeys {
    const val HOME_SHOPPING = "home.category.shopping"
    const val AUTOMATION_GLOBAL = "automation.global.enabled"
    const val HISTORY_IMPORT = "history.import.enabled"
    /** CR-007: normalised order upload; off until the privacy review (R-05). */
    const val HISTORY_UPLOAD = "history.upload.enabled"
    const val COMPARISON = "comparison.enabled"
    const val CHAT_AI = "chat.ai.enabled"

    fun category(c: Category) = "home.category.${c.key}"
}

class FeatureFlags(overrides: Map<String, Boolean> = emptyMap()) {
    private val defaults = mapOf(
        "home.category.travel" to true,
        "home.category.food" to true,
        "home.category.grocery" to true,
        FlagKeys.HOME_SHOPPING to false,
        // Production default is off; mock builds enable it so flows can be exercised.
        FlagKeys.AUTOMATION_GLOBAL to false,
        FlagKeys.HISTORY_IMPORT to false,
        FlagKeys.HISTORY_UPLOAD to false,
        FlagKeys.COMPARISON to true,
        FlagKeys.CHAT_AI to true,
    )
    private val _values = MutableStateFlow(defaults + overrides)
    val values: StateFlow<Map<String, Boolean>> = _values

    fun isOn(key: String): Boolean = _values.value[key] ?: false
    fun set(key: String, on: Boolean) = _values.update { it + (key to on) }
}
