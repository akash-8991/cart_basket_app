package com.smartcart.data

import com.smartcart.model.AuthRules
import com.smartcart.model.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyticsAndRulesTest {

    @Test
    fun analyticsDropsUnknownKeysAndFreeText() {
        val a = LocalAnalyticsCollector(appVersion = "test")
        a.track(
            AnalyticsEventName.CHAT_MESSAGE_SENT,
            mapOf(
                "category" to "grocery",
                "message" to "2 x milk to 12 MG Road", // unknown key: dropped
                "provider" to "Blinkit flat 4B, MG Road", // free text in an allowed key: dropped
                "email" to "someone@example.com",
                "line_count" to 3,
            ),
        )
        val e = a.events.value.single()
        assertEquals("chat_message_sent", e.event)
        assertEquals(setOf("category", "line_count"), e.props.keys)
    }

    @Test
    fun moneyFormatsMinorUnitsWithoutArithmetic() {
        assertEquals("₹229", Money(22900, "INR").format())
        assertEquals("₹1,23,456.50", Money(12345650, "INR").format())
        assertEquals("₹0", Money(0, "INR").format())
    }

    @Test
    fun authRulesMatchContract() {
        assertNull(AuthRules.userIdError("priya.k"))
        assertNotNull(AuthRules.userIdError("Priya K"))
        assertNotNull(AuthRules.userIdError("abc"))
        assertNotNull(AuthRules.passwordError("short"))
        assertNotNull(AuthRules.passwordError("aaaaaaaaaaaa"))
        assertNotNull("password must not contain user ID", AuthRules.passwordError("priya.k-2026!", "priya.k"))
        assertNull(AuthRules.passwordError("correct horse battery"))
        assertNull(AuthRules.emailError("a@b.co"))
        assertNotNull(AuthRules.emailError("not-an-email"))
    }

    @Test
    fun flagsDefaultToProductionSafeValues() {
        val f = FeatureFlags()
        assertFalse("shopping card off by default (D-004)", f.isOn(FlagKeys.HOME_SHOPPING))
        assertFalse("automation off by default in production", f.isOn(FlagKeys.AUTOMATION_GLOBAL))
        assertTrue(f.isOn("home.category.grocery"))
    }
}
