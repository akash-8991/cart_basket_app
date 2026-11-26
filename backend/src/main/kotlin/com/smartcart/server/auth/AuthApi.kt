package com.smartcart.server.auth

import kotlinx.serialization.Serializable

// Wire types for the auth tag of the frozen contract (snake_case via ApiJson). Unknown fields are
// rejected, matching `additionalProperties: false`.

@Serializable
data class DeviceInfo(val deviceId: String, val platform: String, val appVersion: String, val osVersion: String? = null, val deviceModel: String? = null) {
    fun toDevice() = Device(deviceId, platform, appVersion, osVersion, deviceModel?.trim()?.ifBlank { null })
}

@Serializable
data class RegisterRequest(
    val userId: String,
    val email: String,
    val password: String,
    val acceptedTermsVersion: String,
    val acceptedPrivacyVersion: String,
    val device: DeviceInfo,
    /** CR-002 C: false gives a 12-hour session, as on login. */
    val rememberDevice: Boolean = true,
)

@Serializable
data class LoginRequest(val userId: String, val password: String, val device: DeviceInfo, val rememberDevice: Boolean)

@Serializable
data class RefreshRequest(val refreshToken: String, val deviceId: String)

@Serializable
data class ForgotPasswordRequest(val userId: String, val email: String)

@Serializable
data class ForgotUserIdRequest(val email: String)

@Serializable
data class ResetPasswordRequest(val challengeId: String, val code: String, val newPassword: String)

@Serializable
data class TokenPair(val accessToken: String, val refreshToken: String, val accessExpiresInSeconds: Int)

@Serializable
data class AuthSession(
    val userId: String,
    val sessionId: String,
    val tokens: TokenPair,
    val emailVerificationRequired: Boolean,
    val sessionExpiresAt: String?,
)

@Serializable
data class RecoveryAccepted(val message: String, val challengeId: String, val expiresInSeconds: Int)

@Serializable
data class ResetPasswordResponse(val passwordReset: Boolean, val allOtherSessionsRevoked: Boolean)

@Serializable
data class DeviceSession(
    val sessionId: String,
    val deviceLabel: String,
    val platform: String,
    val appVersion: String,
    val approximateLocation: String?,
    val lastSeenAt: String,
    val current: Boolean,
)

@Serializable
data class SessionList(val sessions: List<DeviceSession>)

@Serializable
data class RevokeAllResponse(val revokedCount: Int)
