package com.smartcart.data

import com.smartcart.model.AccountState
import com.smartcart.model.ActivityPage
import com.smartcart.model.AssistantTurn
import com.smartcart.model.CartTask
import com.smartcart.model.Category
import com.smartcart.model.ChatSession
import com.smartcart.model.ComparisonQuote
import com.smartcart.model.Consent
import com.smartcart.model.DashboardSummary
import com.smartcart.model.DataRequestAccepted
import com.smartcart.model.DeleteScope
import com.smartcart.model.DeviceSession
import com.smartcart.model.Handoff
import com.smartcart.model.ImportField
import com.smartcart.model.ImportSession
import com.smartcart.model.MonthlyMetrics
import com.smartcart.model.Order
import com.smartcart.model.PrivacyMode
import com.smartcart.model.Profile
import com.smartcart.model.Provider
import com.smartcart.model.ProviderKey
import com.smartcart.model.QuoteConfirmation
import com.smartcart.model.QuoteRequest
import com.smartcart.model.RecommendationList
import com.smartcart.model.RecoveryAccepted
import com.smartcart.model.ResetPasswordResponse
import com.smartcart.model.StopReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Persists session tokens. The Android implementation is Keystore-backed; the password is never stored. */
interface TokenStore {
    fun save(userId: String, refreshToken: String, remember: Boolean)
    fun read(): StoredSession?
    fun clear()
}

data class StoredSession(val userId: String, val refreshToken: String, val remembered: Boolean)

interface AuthRepository {
    val state: StateFlow<AccountState>
    suspend fun register(userId: String, email: String, password: String): ApiResult<Unit>
    suspend fun login(userId: String, password: String, remember: Boolean): ApiResult<Unit>
    /** Remembered-device continuation using the stored refresh token. */
    suspend fun resume(): ApiResult<Unit>
    suspend fun forgotPassword(userId: String, email: String): ApiResult<RecoveryAccepted>
    suspend fun forgotUserId(email: String): ApiResult<RecoveryAccepted>
    suspend fun resetPassword(challengeId: String, code: String, newPassword: String): ApiResult<ResetPasswordResponse>
    suspend fun logout()
    suspend fun sessions(): ApiResult<List<DeviceSession>>
    suspend fun revokeSession(sessionId: String): ApiResult<Unit>
    suspend fun revokeAllOthers(): ApiResult<Int>
}

interface DashboardRepository {
    suspend fun summary(month: String? = null): ApiResult<DashboardSummary>
    suspend fun monthly(from: String, to: String, provider: ProviderKey? = null, category: Category? = null): ApiResult<MonthlyMetrics>
}

interface ProviderRepository {
    val providers: StateFlow<List<Provider>>
    suspend fun refresh(): ApiResult<List<Provider>>
    suspend fun setAutomationConsent(provider: ProviderKey, granted: Boolean): ApiResult<Unit>
    suspend fun setHistoryConsent(provider: ProviderKey, granted: Boolean): ApiResult<Unit>
}

interface ChatRepository {
    suspend fun createSession(category: Category): ApiResult<ChatSession>
    suspend fun sessions(): ApiResult<List<ChatSession>>
    suspend fun deleteSession(sessionId: String): ApiResult<Unit>
    suspend fun send(sessionId: String, category: Category, text: String, clarificationAnswer: Pair<String, String>? = null): ApiResult<AssistantTurn>
}

interface ComparisonRepository {
    /** Starts a comparison and returns its ID; observe it for progress. */
    suspend fun create(request: QuoteRequest): ApiResult<String>
    fun observe(quoteId: String): Flow<ApiResult<ComparisonQuote>>
    fun cached(quoteId: String): ComparisonQuote?
    suspend fun confirm(quoteId: String, provider: ProviderKey, acknowledgedSwitch: Boolean): ApiResult<QuoteConfirmation>
}

interface CartTaskRepository {
    /** The task that currently needs the global Stop control, if any. */
    val activeTask: StateFlow<CartTask?>
    suspend fun create(quoteId: String, provider: ProviderKey): ApiResult<CartTask>
    fun observe(taskId: String): Flow<CartTask?>
    suspend fun approve(taskId: String, actionIds: List<String>): ApiResult<CartTask>
    suspend fun answerCheckpoint(taskId: String, checkpointId: String, approve: Boolean): ApiResult<CartTask>
    suspend fun resume(taskId: String): ApiResult<CartTask>
    /** Always succeeds locally first: stopping must never wait on the network. */
    suspend fun stop(taskId: String, reason: StopReason): CartTask?
    suspend fun stopAll(reason: StopReason)
    suspend fun createHandoff(taskId: String): ApiResult<Handoff>
    suspend fun markHandedOff(taskId: String)
}

interface HistoryRepository {
    suspend fun start(providers: List<ProviderKey>, fields: Set<ImportField>, mode: PrivacyMode): ApiResult<ImportSession>
    fun observe(sessionId: String): Flow<ImportSession?>
    suspend fun cancel(sessionId: String)
    suspend fun confirmReview(sessionId: String, excludedOrderIds: Set<String>): ApiResult<ImportSession>
    suspend fun orders(provider: ProviderKey? = null): ApiResult<List<Order>>
    suspend fun deleteOrder(orderId: String): ApiResult<Unit>
}

interface RecommendationRepository {
    suspend fun list(category: Category? = null): ApiResult<RecommendationList>
    suspend fun feedback(recommendationId: String, useful: Boolean): ApiResult<Unit>
}

interface AccountRepository {
    val automationPaused: StateFlow<Boolean>
    suspend fun profile(): ApiResult<Profile>
    suspend fun consents(): ApiResult<List<Consent>>
    suspend fun setConsent(key: String, granted: Boolean): ApiResult<Consent>
    suspend fun activity(): ApiResult<ActivityPage>
    suspend fun export(password: String): ApiResult<DataRequestAccepted>
    suspend fun delete(scope: DeleteScope, password: String): ApiResult<DataRequestAccepted>
    suspend fun setPrivacyMode(mode: PrivacyMode): ApiResult<Profile>
    fun setAutomationPaused(paused: Boolean)
}
