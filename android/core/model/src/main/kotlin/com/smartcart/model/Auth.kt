package com.smartcart.model

import kotlinx.serialization.Serializable

@Serializable
data class DeviceInfo(
    val deviceId: String,
    val platform: String = "android",
    val appVersion: String,
    val osVersion: String? = null,
    /** CR-002 B: shown in the sessions list, e.g. "Pixel 8". */
    val deviceModel: String? = null,
)

@Serializable
data class RegisterRequest(
    val userId: String,
    val email: String,
    val password: String,
    val acceptedTermsVersion: String,
    val acceptedPrivacyVersion: String,
    val device: DeviceInfo,
    /** CR-002 C: false gives a 12-hour session. */
    val rememberDevice: Boolean = true,
)

@Serializable
data class LoginRequest(
    val userId: String,
    val password: String,
    val device: DeviceInfo,
    val rememberDevice: Boolean,
)

@Serializable
data class TokenPair(
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresInSeconds: Int,
)

@Serializable
data class AuthSession(
    val userId: String,
    val sessionId: String,
    val tokens: TokenPair,
    val emailVerificationRequired: Boolean,
    val sessionExpiresAt: String? = null,
)

@Serializable
data class RecoveryAccepted(
    val message: String,
    val challengeId: String,
    val expiresInSeconds: Int,
)

@Serializable
data class ResetPasswordResponse(
    val passwordReset: Boolean,
    val allOtherSessionsRevoked: Boolean,
)

@Serializable
data class DeviceSession(
    val sessionId: String,
    val deviceLabel: String,
    val platform: String,
    val appVersion: String,
    val approximateLocation: String? = null,
    val lastSeenAt: String,
    val current: Boolean,
)

@Serializable
data class SessionList(val sessions: List<DeviceSession>)

@Serializable
data class RevokeAllResponse(val revokedCount: Int)

/** Client account state. Never holds the password. */
sealed interface AccountState {
    data object Loading : AccountState
    data object SignedOut : AccountState
    /** A remembered device session exists; the user continues without re-entering the password. */
    data class Remembered(val userId: String) : AccountState
    data class SignedIn(val userId: String, val emailVerificationRequired: Boolean = false) : AccountState
}

/** Client-side validation problems; the app maps each to a string resource. */
enum class FieldProblem { TOO_SHORT, TOO_LONG, INVALID_CHARACTERS, INVALID_EMAIL, CONTAINS_USER_ID, TOO_REPETITIVE }

/** Client-side validation mirrors server rules in the contract; the server remains authoritative. */
object AuthRules {
    val USER_ID = Regex("^[a-z0-9._-]{4,30}$")
    const val USER_ID_MIN = 4
    const val USER_ID_MAX = 30
    const val PASSWORD_MIN = 10
    const val PASSWORD_MAX = 128
    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    fun userIdError(v: String): FieldProblem? = when {
        v.length < USER_ID_MIN -> FieldProblem.TOO_SHORT
        v.length > USER_ID_MAX -> FieldProblem.TOO_LONG
        !USER_ID.matches(v) -> FieldProblem.INVALID_CHARACTERS
        else -> null
    }

    fun emailError(v: String): FieldProblem? = if (EMAIL.matches(v.trim())) null else FieldProblem.INVALID_EMAIL

    fun passwordError(v: String, userId: String = ""): FieldProblem? = when {
        v.length < PASSWORD_MIN -> FieldProblem.TOO_SHORT
        v.length > PASSWORD_MAX -> FieldProblem.TOO_LONG
        userId.isNotBlank() && v.contains(userId, ignoreCase = true) -> FieldProblem.CONTAINS_USER_ID
        v.toSet().size < 5 -> FieldProblem.TOO_REPETITIVE
        else -> null
    }
}
