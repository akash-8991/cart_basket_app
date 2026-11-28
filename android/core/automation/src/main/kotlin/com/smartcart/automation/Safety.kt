package com.smartcart.automation

import com.smartcart.model.UserActionReason

/**
 * Screens the runtime must never act on (plan rule 3, threat model §4 "Prohibited"). Each maps to what the
 * user is told: payment ends the task as BLOCKED_POLICY; the rest hand the phone back (NEEDS_USER_ACTION).
 */
enum class Hazard(val userReason: UserActionReason?) {
    PAYMENT(null),
    LOGIN(UserActionReason.LOGIN_REQUIRED),
    PASSWORD(UserActionReason.PASSWORD_PROMPT),
    OTP(UserActionReason.OTP_REQUIRED),
    CAPTCHA(UserActionReason.CAPTCHA),
    BIOMETRIC(UserActionReason.BIOMETRIC),
}

object Safety {
    private fun rx(vararg words: String) = Regex(words.joinToString("|", prefix = "\\b(", postfix = ")\\b"), RegexOption.IGNORE_CASE)

    private val OTP = rx("otp", "one[- ]time password", "one[- ]time code", "verification code", "enter the code", "sms code")
    private val CAPTCHA = rx("captcha", "i'?m not a robot", "verify (that )?you('re| are) (a )?human", "security check")
    private val BIOMETRIC = rx("fingerprint", "face unlock", "biometric", "use your screen lock", "touch the sensor")
    private val LOGIN = rx("log ?in", "sign ?in", "username", "email address", "mobile number")
    private val PAYMENT_FIELD = rx("card number", "cvv", "cvc", "expiry", "upi id", "upi pin", "vpa", "net ?banking", "account number", "ifsc")

    /** Controls that would commit money or an order. Never tapped, whatever an adapter asks (defence in depth). */
    private val COMMIT_CONTROL = rx(
        "pay", "pay now", "payment", "place order", "checkout", "check out", "proceed to pay", "buy now",
        "book", "book now", "confirm ride", "confirm booking", "confirm order", "request ride", "slide to pay", "complete purchase",
    )

    /**
     * What makes this screen off-limits, if anything. Input fields are always decisive (a password, OTP or
     * card field is a hazard on any screen). Wording alone only counts on screens the adapter doesn't
     * recognise, so a cart that mentions "OTP on delivery" isn't mistaken for an OTP prompt.
     */
    fun hazard(s: ScreenSnapshot, known: ScreenSpec?): Hazard? {
        known?.hazard?.let { return it }
        val fields = s.nodes.filter { it.editable || it.password }
        if (fields.any { it.password }) return if (s.nodes.any { LOGIN.containsMatchIn(it.words) }) Hazard.LOGIN else Hazard.PASSWORD
        fields.forEach { f ->
            val w = f.words + " " + (f.id ?: "")
            when {
                PAYMENT_FIELD.containsMatchIn(w) -> return Hazard.PAYMENT
                OTP.containsMatchIn(w) -> return Hazard.OTP
                CAPTCHA.containsMatchIn(w) -> return Hazard.CAPTCHA
            }
        }
        if (known != null) return null
        val all = s.nodes.joinToString(" ") { it.words }
        return when {
            CAPTCHA.containsMatchIn(all) -> Hazard.CAPTCHA
            BIOMETRIC.containsMatchIn(all) -> Hazard.BIOMETRIC
            OTP.containsMatchIn(all) -> Hazard.OTP
            PAYMENT_FIELD.containsMatchIn(all) -> Hazard.PAYMENT
            else -> null
        }
    }

    /** True for a control that commits money or an order, or for any secret-entry field. */
    fun forbiddenToTap(n: UiNode): Boolean = COMMIT_CONTROL.containsMatchIn(n.words) || COMMIT_CONTROL.containsMatchIn(idWords(n))

    /** Never type into passwords, OTP, card or UPI fields. */
    fun forbiddenToType(n: UiNode): Boolean = n.password || (n.words + " " + idWords(n)).let { PAYMENT_FIELD.containsMatchIn(it) || OTP.containsMatchIn(it) }

    private fun idWords(n: UiNode) = n.id?.substringAfter(":id/")?.replace('_', ' ').orEmpty()
}
