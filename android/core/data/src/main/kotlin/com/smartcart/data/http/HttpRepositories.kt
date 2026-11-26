package com.smartcart.data.http

import com.smartcart.data.AccountRepository
import com.smartcart.data.ApiJson
import com.smartcart.data.ApiResult
import com.smartcart.data.AuthRepository
import com.smartcart.data.ChatRepository
import com.smartcart.data.ProviderRepository
import com.smartcart.data.TokenStore
import com.smartcart.data.map
import com.smartcart.model.AccountState
import com.smartcart.model.ActivityPage
import com.smartcart.model.AssistantTurn
import com.smartcart.model.AuthSession
import com.smartcart.model.Category
import com.smartcart.model.ChatSession
import com.smartcart.model.ChatSessionPage
import com.smartcart.model.Consent
import com.smartcart.model.ConsentList
import com.smartcart.model.DataRequestAccepted
import com.smartcart.model.DeleteScope
import com.smartcart.model.DeviceInfo
import com.smartcart.model.DeviceSession
import com.smartcart.model.LoginRequest
import com.smartcart.model.PrivacyMode
import com.smartcart.model.Profile
import com.smartcart.model.Provider
import com.smartcart.model.ProviderKey
import com.smartcart.model.ProviderList
import com.smartcart.model.RecoveryAccepted
import com.smartcart.model.RegisterRequest
import com.smartcart.model.ResetPasswordResponse
import com.smartcart.model.RevokeAllResponse
import com.smartcart.model.SessionList
import com.smartcart.model.TokenPair
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import java.util.Locale

// Request bodies the shared model doesn't define (snake_case via ApiJson).
@Serializable private data class RefreshRequest(val refreshToken: String, val deviceId: String)
@Serializable private data class ForgotPasswordRequest(val userId: String, val email: String)
@Serializable private data class ForgotUserIdRequest(val email: String)
@Serializable private data class ResetPasswordRequest(val challengeId: String, val code: String, val newPassword: String)
@Serializable private data class SetConsentRequest(val granted: Boolean, val version: String)
@Serializable private data class StepUp(val password: String)
@Serializable private data class DeleteDataRequest(val scope: String, val password: String)
@Serializable private data class CreateChatSessionRequest(val category: Category)
@Serializable private data class ClarificationAnswer(val clarificationId: String, val optionId: String)
@Serializable private data class ChatContext(val installedProviders: List<ProviderKey>, val locale: String, val currency: String)
@Serializable private data class ChatMessageRequest(val sessionId: String, val text: String, val clarificationAnswer: ClarificationAnswer? = null, val context: ChatContext)

private inline fun <reified T> encode(serializer: KSerializer<T>, value: T) = ApiJson.lenient.encodeToString(serializer, value)

/** Versions of the terms, privacy and consent texts shipped in this build of the frozen frontend. */
object PolicyVersions {
    const val TERMS = "2026-11"
    const val PRIVACY = "2026-11"
    const val CONSENT = "2026-11"
}

class HttpAuthRepository(
    private val api: ApiClient,
    private val store: TokenStore,
    private val device: () -> DeviceInfo,
) : AuthRepository, SessionTokens {
    private val _state = MutableStateFlow<AccountState>(
        store.read()?.let { if (it.remembered) AccountState.Remembered(it.userId) else null } ?: AccountState.SignedOut,
    )
    override val state: StateFlow<AccountState> = _state

    @Volatile override var accessToken: String? = null
        private set

    init { api.tokens = this }

    private fun signedIn(s: AuthSession, remember: Boolean) {
        accessToken = s.tokens.accessToken
        store.save(s.userId, s.tokens.refreshToken, remember)
        _state.value = AccountState.SignedIn(s.userId, s.emailVerificationRequired)
    }

    private fun signedOut() {
        accessToken = null
        store.clear()
        _state.value = AccountState.SignedOut
    }

    override suspend fun register(userId: String, email: String, password: String): ApiResult<Unit> =
        api.call("POST", "/v1/auth/register", AuthSession.serializer(),
            encode(RegisterRequest.serializer(), RegisterRequest(userId, email, password, PolicyVersions.TERMS, PolicyVersions.PRIVACY, device())),
            authenticated = false, idempotent = true,
        ).map { signedIn(it, remember = true) }

    override suspend fun login(userId: String, password: String, remember: Boolean): ApiResult<Unit> =
        api.call("POST", "/v1/auth/login", AuthSession.serializer(),
            encode(LoginRequest.serializer(), LoginRequest(userId, password, device(), remember)), authenticated = false,
        ).map { signedIn(it, remember) }

    /** Remembered-device continuation: a refresh with the stored token. */
    override suspend fun resume(): ApiResult<Unit> {
        val stored = store.read() ?: return ApiResult.Failure(com.smartcart.model.ApiError(com.smartcart.model.ErrorCode.SESSION_EXPIRED, "Please sign in again.", "client"))
        return when (val r = refreshWith(stored.refreshToken)) {
            is ApiResult.Success -> { _state.value = AccountState.SignedIn(stored.userId); ApiResult.Success(Unit) }
            is ApiResult.Failure -> r
        }
    }

    override suspend fun refresh(): Boolean {
        val stored = store.read() ?: return false.also { signedOut() }
        return refreshWith(stored.refreshToken) is ApiResult.Success
    }

    private suspend fun refreshWith(refreshToken: String): ApiResult<TokenPair> {
        val r = api.call("POST", "/v1/auth/refresh", TokenPair.serializer(),
            encode(RefreshRequest.serializer(), RefreshRequest(refreshToken, device().deviceId)), authenticated = false)
        when (r) {
            is ApiResult.Success -> {
                val stored = store.read()!!
                accessToken = r.value.accessToken
                store.save(stored.userId, r.value.refreshToken, stored.remembered) // rotated: the old one is now spent
            }
            is ApiResult.Failure -> if (r.error.code == com.smartcart.model.ErrorCode.SESSION_EXPIRED) signedOut()
        }
        return r
    }

    override suspend fun forgotPassword(userId: String, email: String): ApiResult<RecoveryAccepted> =
        api.call("POST", "/v1/auth/forgot-password", RecoveryAccepted.serializer(), encode(ForgotPasswordRequest.serializer(), ForgotPasswordRequest(userId, email)), authenticated = false)

    override suspend fun forgotUserId(email: String): ApiResult<RecoveryAccepted> =
        api.call("POST", "/v1/auth/forgot-user-id", RecoveryAccepted.serializer(), encode(ForgotUserIdRequest.serializer(), ForgotUserIdRequest(email)), authenticated = false)

    override suspend fun resetPassword(challengeId: String, code: String, newPassword: String): ApiResult<ResetPasswordResponse> =
        api.call("POST", "/v1/auth/reset-password", ResetPasswordResponse.serializer(), encode(ResetPasswordRequest.serializer(), ResetPasswordRequest(challengeId, code, newPassword)), authenticated = false)

    /** Ends the session on the server when reachable; always ends it on the device. */
    override suspend fun logout() {
        if (accessToken != null) api.call<Unit>("POST", "/v1/auth/logout", null)
        signedOut()
    }

    override suspend fun sessions(): ApiResult<List<DeviceSession>> = api.call("GET", "/v1/auth/sessions", SessionList.serializer()).map { it.sessions }

    override suspend fun revokeSession(sessionId: String): ApiResult<Unit> = api.call("DELETE", "/v1/auth/sessions/$sessionId", null)

    override suspend fun revokeAllOthers(): ApiResult<Int> = api.call("POST", "/v1/auth/sessions/revoke-all", RevokeAllResponse.serializer()).map { it.revokedCount }

    /** Signs out locally after the server has signed out every device (account deletion). */
    internal fun forget() = signedOut()
}

class HttpAccountRepository(private val api: ApiClient, private val auth: HttpAuthRepository) : AccountRepository {
    private val _paused = MutableStateFlow(false)
    override val automationPaused: StateFlow<Boolean> = _paused
    // No contract operation sets privacy mode yet (Phase 3 contract gap 7): kept on the device until there is one.
    private var localPrivacyMode: PrivacyMode? = null

    override suspend fun profile(): ApiResult<Profile> =
        api.call("GET", "/v1/me", Profile.serializer()).map { p -> localPrivacyMode?.let { p.copy(privacyMode = it) } ?: p }

    override suspend fun consents(): ApiResult<List<Consent>> = api.call("GET", "/v1/me/consents", ConsentList.serializer()).map { it.consents }

    override suspend fun setConsent(key: String, granted: Boolean): ApiResult<Consent> =
        api.call("PUT", "/v1/me/consents/$key", Consent.serializer(), encode(SetConsentRequest.serializer(), SetConsentRequest(granted, PolicyVersions.CONSENT)))

    override suspend fun activity(): ApiResult<ActivityPage> = api.call("GET", "/v1/me/activity", ActivityPage.serializer())

    override suspend fun export(password: String): ApiResult<DataRequestAccepted> =
        api.call("POST", "/v1/me/data/export", DataRequestAccepted.serializer(), encode(StepUp.serializer(), StepUp(password)), idempotent = true)

    override suspend fun delete(scope: DeleteScope, password: String): ApiResult<DataRequestAccepted> {
        val r = api.call("POST", "/v1/me/data/delete", DataRequestAccepted.serializer(),
            encode(DeleteDataRequest.serializer(), DeleteDataRequest(scope.key, password)), idempotent = true)
        if (r is ApiResult.Success && scope == DeleteScope.ACCOUNT) auth.forget() // the server has already signed out every device
        return r
    }

    override suspend fun setPrivacyMode(mode: PrivacyMode): ApiResult<Profile> {
        localPrivacyMode = mode
        return profile()
    }

    override fun setAutomationPaused(paused: Boolean) { _paused.value = paused }
}

/** [installed] reports whether a provider's app is on this device; the server can't know (contract: installed is client-filled). */
class HttpProviderRepository(
    private val api: ApiClient,
    private val account: AccountRepository,
    private val installed: (ProviderKey) -> Boolean? = { null },
) : ProviderRepository {
    private val _providers = MutableStateFlow<List<Provider>>(emptyList())
    override val providers: StateFlow<List<Provider>> = _providers

    override suspend fun refresh(): ApiResult<List<Provider>> =
        api.call("GET", "/v1/providers", ProviderList.serializer())
            .map { list -> list.providers.map { it.copy(installed = installed(it.key)) }.also { _providers.value = it } }

    override suspend fun setAutomationConsent(provider: ProviderKey, granted: Boolean): ApiResult<Unit> =
        account.setConsent("automation.${provider.key}", granted).also { refresh() }.map { }

    override suspend fun setHistoryConsent(provider: ProviderKey, granted: Boolean): ApiResult<Unit> =
        account.setConsent("history.${provider.key}", granted).also { refresh() }.map { }
}

class HttpChatRepository(
    private val api: ApiClient,
    private val providers: ProviderRepository,
    private val locale: () -> Locale = { Locale.getDefault() },
) : ChatRepository {
    override suspend fun createSession(category: Category): ApiResult<ChatSession> =
        api.call("POST", "/v1/chat/sessions", ChatSession.serializer(), encode(CreateChatSessionRequest.serializer(), CreateChatSessionRequest(category)), idempotent = true)

    override suspend fun sessions(): ApiResult<List<ChatSession>> = api.call("GET", "/v1/chat/sessions", ChatSessionPage.serializer()).map { it.sessions }

    override suspend fun deleteSession(sessionId: String): ApiResult<Unit> = api.call("DELETE", "/v1/chat/sessions/$sessionId", null)

    override suspend fun send(sessionId: String, category: Category, text: String, clarificationAnswer: Pair<String, String>?): ApiResult<AssistantTurn> {
        val installed = providers.providers.value.filter { it.installed == true }.map { it.key }
        val body = ChatMessageRequest(
            sessionId = sessionId,
            text = text,
            clarificationAnswer = clarificationAnswer?.let { ClarificationAnswer(it.first, it.second) },
            context = ChatContext(installed, locale().toLanguageTag(), "INR"),
        )
        return api.call("POST", "/v1/chat/messages", AssistantTurn.serializer(), encode(ChatMessageRequest.serializer(), body), idempotent = true)
    }
}

