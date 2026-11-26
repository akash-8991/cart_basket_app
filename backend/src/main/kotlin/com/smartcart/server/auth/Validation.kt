package com.smartcart.server.auth

import com.smartcart.server.http.ApiException
import com.smartcart.server.http.ErrorCode
import com.smartcart.server.http.FieldError

/** Field rules from the contract's request schemas, reported as field_errors in one response. */
class Validator {
    private val errors = mutableListOf<FieldError>()

    fun check(ok: Boolean, field: String, code: String) { if (!ok) errors += FieldError(field, code) }

    fun userId(v: String, field: String = "user_id") = check(USER_ID.matches(v), field, "invalid_format")
    fun email(v: String, field: String = "email") = check(v.length <= 254 && EMAIL.matches(v), field, "invalid_format")
    fun password(v: String, field: String = "password") = check(v.length in 10..128, field, "invalid_length")
    fun nonBlank(v: String, field: String, max: Int = 64) = check(v.isNotBlank() && v.length <= max, field, "invalid_format")
    fun device(d: DeviceInfo, prefix: String = "device") {
        nonBlank(d.deviceId, "$prefix.device_id", 128)
        check(d.platform == "android", "$prefix.platform", "invalid_value")
        nonBlank(d.appVersion, "$prefix.app_version", 32)
        d.osVersion?.let { check(it.length <= 32, "$prefix.os_version", "invalid_format") }
    }

    fun throwIfInvalid() { if (errors.isNotEmpty()) throw ApiException(ErrorCode.VALIDATION_FAILED, errors.toList()) }

    companion object {
        val USER_ID = Regex("^[a-z0-9._-]{4,30}$")
        // Deliberately simple: the address is proven by mail delivery, not by a regex.
        val EMAIL = Regex("^[^@\\s]{1,64}@[^@\\s]+\\.[^@\\s]{2,}$")

        inline fun validate(block: Validator.() -> Unit) = Validator().apply(block).throwIfInvalid()
    }
}
