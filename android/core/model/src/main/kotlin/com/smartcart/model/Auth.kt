package com.smartcart.model

import kotlinx.serialization.Serializable

@Serializable
data class DeviceInfo(
    val deviceId: String,
    val platform: String = "android",
    val appVersion: String,
    val osVersion: String? = null,
)

@Serializable
data class RegisterRequest(
    val userId: String,
    val email: String,
    val password: String,
    val acceptedTermsVersion: String,
    val acceptedPrivacyVersion: String,
    val device: DeviceInfo,
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

/** Client-side validation mirrors server rules in the contract; the server remains authoritative. */
object AuthRules {
    val USER_ID = Regex("^[a-z0-9._-]{4,30}$")
    const val PASSWORD_MIN = 10
    const val PASSWORD_MAX = 128
    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    fun userIdError(v: String): String? = when {
        v.length < 4 -> "Use at least 4 characters"
        v.length > 30 -> "Use at most 30 characters"
        !USER_ID.matches(v) -> "Use lowercase letters, numbers, dot, dash or underscore"
        else -> null
    }

    fun emailError(v: String): String? = if (EMAIL.matches(v.trim())) null else "Enter a valid email address"

    fun passwordError(v: String, userId: String = ""): String? = when {
        v.length < PASSWORD_MIN -> "Use at least $PASSWORD_MIN characters"
        v.length > PASSWORD_MAX -> "Use at most $PASSWORD_MAX characters"
        userId.isNotBlank() && v.contains(userId, ignoreCase = true) -> "Don't include your user ID"
        v.toSet().size < 5 -> "Use a less repetitive password"
        else -> null
    }
}
