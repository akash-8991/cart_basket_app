package com.smartcart.data.mock

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Debug-only scenario switches. Each value selects which contract fixture (or simulated
 * behaviour) a fake repository returns, so every screen state in the catalogue can be shown.
 */
data class MockConfig(
    val latencyMs: Long = 700,
    val offline: Boolean = false,
    val login: LoginScenario = LoginScenario.SUCCESS,
    val register: RegisterScenario = RegisterScenario.SUCCESS,
    val sessionRestore: SessionRestoreScenario = SessionRestoreScenario.VALID,
    val resetPassword: ResetScenario = ResetScenario.SUCCESS,
    val recovery: RecoveryScenario = RecoveryScenario.ACCEPTED,
    val dashboard: DashboardScenario = DashboardScenario.SUCCESS,
    val chat: ChatScenario = ChatScenario.CLARIFY_FIRST,
    val comparison: ComparisonScenario = ComparisonScenario.AUTO,
    val task: TaskScenario = TaskScenario.CART_READY,
    val handoff: HandoffScenario = HandoffScenario.CART,
    val history: ListScenario = ListScenario.SUCCESS,
    val suggestions: ListScenario = ListScenario.SUCCESS,
    val activity: ListScenario = ListScenario.SUCCESS,
    val importFlow: ImportScenario = ImportScenario.ONE_PROVIDER_FAILS,
)

enum class LoginScenario { SUCCESS, INVALID_CREDENTIALS, ACCOUNT_LOCKED, SERVER_ERROR }
enum class RegisterScenario { SUCCESS, USER_ID_UNAVAILABLE, WEAK_PASSWORD, SERVER_ERROR }
enum class SessionRestoreScenario { VALID, EXPIRED }
enum class ResetScenario { SUCCESS, EXPIRED }
enum class RecoveryScenario { ACCEPTED, RATE_LIMITED }
enum class DashboardScenario { SUCCESS, STALE, EMPTY, ERROR }
enum class ChatScenario { CLARIFY_FIRST, DIRECT, DEGRADED, ERROR }
enum class ComparisonScenario { AUTO, PARTIAL_COVERAGE, STALE, INCOMPARABLE, UNAVAILABLE, PRICE_CHANGED, MANUAL_ONLY, ERROR }
enum class TaskScenario { CART_READY, CHECKPOINT, NEEDS_USER_ACTION, FAILED_SAFE, BLOCKED_POLICY, PROVIDER_DISABLED }
enum class HandoffScenario { CART, APP_LAUNCH, MANUAL }
enum class ListScenario { SUCCESS, EMPTY, ERROR }
enum class ImportScenario { ALL_SUCCEED, ONE_PROVIDER_FAILS }

class MockControl(initial: MockConfig = MockConfig()) {
    private val _config = MutableStateFlow(initial)
    val config: StateFlow<MockConfig> = _config
    val current: MockConfig get() = _config.value

    fun update(f: (MockConfig) -> MockConfig) = _config.update(f)

    suspend fun latency(factor: Double = 1.0) {
        val ms = (current.latencyMs * factor).toLong()
        if (ms > 0) delay(ms)
    }
}
