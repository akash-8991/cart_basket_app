package com.smartcart.data.fake

import com.smartcart.data.ApiResult
import com.smartcart.data.AuthRepository
import com.smartcart.data.Fixtures
import com.smartcart.data.TokenStore
import com.smartcart.data.mock.LoginScenario
import com.smartcart.data.mock.MockControl
import com.smartcart.data.mock.RecoveryScenario
import com.smartcart.data.mock.RegisterScenario
import com.smartcart.data.mock.ResetScenario
import com.smartcart.data.mock.SessionRestoreScenario
import com.smartcart.model.AccountState
import com.smartcart.model.AuthSession
import com.smartcart.model.DeviceSession
import com.smartcart.model.RecoveryAccepted
import com.smartcart.model.ResetPasswordResponse
import com.smartcart.model.RevokeAllResponse
import com.smartcart.model.SessionList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeAuthRepository(
    fixtures: Fixtures,
    mock: MockControl,
    private val tokens: TokenStore,
) : FakeBase(fixtures, mock), AuthRepository {

    private val _state = MutableStateFlow<AccountState>(
        tokens.read()?.let { if (it.remembered) AccountState.Remembered(it.userId) else null } ?: AccountState.SignedOut,
    )
    override val state: StateFlow<AccountState> = _state

    private var sessions: List<DeviceSession> = fixtures.load<SessionList>("auth/sessions.json").sessions

    override suspend fun register(userId: String, email: String, password: String): ApiResult<Unit> = call {
        when (cfg.register) {
            RegisterScenario.SUCCESS -> {
                val s = fixtures.load<AuthSession>("auth/register.success.json")
                tokens.save(userId, s.tokens.refreshToken, remember = true)
                _state.value = AccountState.SignedIn(userId, s.emailVerificationRequired)
                ApiResult.Success(Unit)
            }
            RegisterScenario.USER_ID_UNAVAILABLE -> fixtures.error("auth/register.user-id-unavailable.json")
            RegisterScenario.WEAK_PASSWORD -> fixtures.error("auth/register.weak-password.json")
            RegisterScenario.SERVER_ERROR -> serviceError()
        }
    }

    override suspend fun login(userId: String, password: String, remember: Boolean): ApiResult<Unit> = call {
        when (cfg.login) {
            LoginScenario.SUCCESS -> {
                val s = fixtures.load<AuthSession>("auth/login.success.json")
                tokens.save(userId, s.tokens.refreshToken, remember)
                _state.value = AccountState.SignedIn(userId)
                ApiResult.Success(Unit)
            }
            LoginScenario.INVALID_CREDENTIALS -> fixtures.error("auth/login.invalid-credentials.json")
            LoginScenario.ACCOUNT_LOCKED -> fixtures.error("auth/login.account-locked.json")
            LoginScenario.SERVER_ERROR -> serviceError()
        }
    }

    override suspend fun resume(): ApiResult<Unit> = call {
        val stored = tokens.read()
        if (stored == null || cfg.sessionRestore == SessionRestoreScenario.EXPIRED) {
            tokens.clear()
            _state.value = AccountState.SignedOut
            fixtures.error("auth/refresh.session-expired.json")
        } else {
            _state.value = AccountState.SignedIn(stored.userId)
            ApiResult.Success(Unit)
        }
    }

    override suspend fun forgotPassword(userId: String, email: String): ApiResult<RecoveryAccepted> = call {
        // Identical response whether or not the details match (enumeration resistance).
        if (cfg.recovery == RecoveryScenario.RATE_LIMITED) fixtures.error("auth/recovery.rate-limited.json")
        else ApiResult.Success(fixtures.load("auth/forgot-password.accepted.json"))
    }

    override suspend fun forgotUserId(email: String): ApiResult<RecoveryAccepted> = call {
        if (cfg.recovery == RecoveryScenario.RATE_LIMITED) fixtures.error("auth/recovery.rate-limited.json")
        else ApiResult.Success(fixtures.load("auth/forgot-user-id.accepted.json"))
    }

    override suspend fun resetPassword(challengeId: String, code: String, newPassword: String): ApiResult<ResetPasswordResponse> = call {
        when (cfg.resetPassword) {
            ResetScenario.SUCCESS -> {
                sessions = sessions.filter { it.current }
                ApiResult.Success(fixtures.load("auth/reset-password.success.json"))
            }
            ResetScenario.EXPIRED -> fixtures.error("auth/reset-password.expired.json")
        }
    }

    override suspend fun logout() {
        tokens.clear()
        _state.value = AccountState.SignedOut
    }

    override suspend fun sessions(): ApiResult<List<DeviceSession>> = call { ApiResult.Success(sessions) }

    override suspend fun revokeSession(sessionId: String): ApiResult<Unit> = call {
        sessions = sessions.filterNot { it.sessionId == sessionId && !it.current }
        ApiResult.Success(Unit)
    }

    override suspend fun revokeAllOthers(): ApiResult<Int> = call {
        val count = fixtures.load<RevokeAllResponse>("auth/revoke-all.json").revokedCount
        sessions = sessions.filter { it.current }
        ApiResult.Success(count)
    }
}
