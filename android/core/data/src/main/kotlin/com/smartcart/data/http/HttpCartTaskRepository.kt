package com.smartcart.data.http

import com.smartcart.data.ApiResult
import com.smartcart.data.CartTaskRepository
import com.smartcart.data.ComparisonRepository
import com.smartcart.data.clientError
import com.smartcart.data.device.TaskServer
import com.smartcart.data.map
import com.smartcart.model.ApproveCartTaskRequest
import com.smartcart.model.CartTask
import com.smartcart.model.Category
import com.smartcart.model.CheckpointChoice
import com.smartcart.model.CheckpointDecision
import com.smartcart.model.CreateCartTaskRequest
import com.smartcart.model.ErrorCode
import com.smartcart.model.Handoff
import com.smartcart.model.HandoffRequest
import com.smartcart.model.ProviderKey
import com.smartcart.model.StopCartTaskRequest
import com.smartcart.model.StopReason
import com.smartcart.model.TaskEvent
import com.smartcart.model.TaskEventType
import com.smartcart.model.TaskProgressReport
import com.smartcart.model.TaskStatus
import com.smartcart.model.TaskType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Cart tasks held by the server (Phase 4.5): created on the confirmed comparison, approved with the server's
 * single-use nonce, stopped and handed off there. Tasks the server runs itself (in development, those the phone
 * doesn't automate) are followed by polling while they are active. It is also the [TaskServer] a task the phone
 * runs reports to.
 */
class HttpCartTaskRepository(
    private val api: ApiClient,
    private val comparisons: ComparisonRepository,
    private val scope: CoroutineScope,
    private val pollMillis: Long = 1_000,
) : CartTaskRepository, TaskServer {
    private val tasks = MutableStateFlow<Map<String, CartTask>>(emptyMap())
    private val _active = MutableStateFlow<CartTask?>(null)
    override val activeTask: StateFlow<CartTask?> = _active
    private val pollers = ConcurrentHashMap<String, Job>()

    private fun put(t: CartTask): CartTask {
        tasks.value = tasks.value + (t.taskId to t)
        _active.value = tasks.value.values.firstOrNull { it.status.isActive }
        return t
    }

    private fun ApiResult<CartTask>.kept(): ApiResult<CartTask> = also { if (it is ApiResult.Success) put(it.value) }

    // ------------------------------------------------------------------ TaskServer

    override suspend fun createTask(quoteId: String, provider: ProviderKey): ApiResult<CartTask> {
        val type = if (comparisons.cached(quoteId)?.category == Category.TRAVEL) TaskType.RIDE_SETUP else TaskType.ADD_TO_CART
        return api.call("POST", "/v1/cart-tasks", CartTask.serializer(), encode(CreateCartTaskRequest.serializer(), CreateCartTaskRequest(quoteId, provider, type)), idempotent = true)
    }

    override suspend fun approveTask(taskId: String, actionIds: List<String>, nonce: String): ApiResult<CartTask> =
        api.call("POST", "/v1/cart-tasks/$taskId/approve", CartTask.serializer(), encode(ApproveCartTaskRequest.serializer(), ApproveCartTaskRequest(actionIds, nonce)), idempotent = true)

    override suspend fun reportProgress(taskId: String, report: TaskProgressReport): ApiResult<CartTask> =
        api.call("POST", "/v1/cart-tasks/$taskId/progress", CartTask.serializer(), encode(TaskProgressReport.serializer(), report))

    override suspend fun answerCheckpoint(taskId: String, checkpointId: String, approve: Boolean): ApiResult<CartTask> {
        val body = CheckpointDecision(checkpointId, if (approve) CheckpointChoice.APPROVE else CheckpointChoice.REJECT)
        return api.call("POST", "/v1/cart-tasks/$taskId/checkpoint", CartTask.serializer(), encode(CheckpointDecision.serializer(), body), idempotent = true).also { if (it is ApiResult.Success && tasks.value.containsKey(taskId)) put(it.value) }
    }

    override suspend fun stopTask(taskId: String, reason: StopReason): ApiResult<CartTask> =
        api.call("POST", "/v1/cart-tasks/$taskId/stop", CartTask.serializer(), encode(StopCartTaskRequest.serializer(), StopCartTaskRequest(reason))).also { if (it is ApiResult.Success && tasks.value.containsKey(taskId)) put(it.value) }

    override suspend fun handoff(taskId: String): ApiResult<Handoff> {
        val h = api.call("POST", "/v1/deep-links", Handoff.serializer(), encode(HandoffRequest.serializer(), HandoffRequest(taskId)), idempotent = true)
        if (h is ApiResult.Success && tasks.value.containsKey(taskId)) refresh(taskId)
        return h
    }

    override suspend fun getTask(taskId: String): ApiResult<CartTask> = api.call("GET", "/v1/cart-tasks/$taskId", CartTask.serializer())

    /** Reads a task this repository follows and keeps the answer. */
    private suspend fun refresh(taskId: String) = getTask(taskId).kept()

    // ------------------------------------------------------------------ CartTaskRepository

    override suspend fun create(quoteId: String, provider: ProviderKey) = createTask(quoteId, provider).kept()

    override fun observe(taskId: String): Flow<CartTask?> = tasks.map { it[taskId] }.distinctUntilChanged()

    override suspend fun approve(taskId: String, actionIds: List<String>): ApiResult<CartTask> {
        val nonce = tasks.value[taskId]?.approvalNonce ?: return clientError(ErrorCode.TASK_STATE_CONFLICT, "This task has moved on. Refresh and try again.")
        return approveTask(taskId, actionIds, nonce).kept().also { if (it is ApiResult.Success) follow(taskId) }
    }

    /** Polls a task the server runs until it is no longer active. */
    private fun follow(taskId: String) {
        pollers[taskId]?.cancel()
        pollers[taskId] = scope.launch {
            while (true) {
                val t = tasks.value[taskId] ?: break
                if (!t.status.isActive) break
                delay(pollMillis)
                if (refresh(taskId) !is ApiResult.Success) delay(pollMillis)
            }
        }
    }

    /** Only a task the phone runs can wait for the person; the server's own runs never do. */
    override suspend fun resume(taskId: String): ApiResult<CartTask> = clientError(ErrorCode.TASK_STATE_CONFLICT, "This task has moved on. Refresh and try again.")

    /** Takes effect locally at once; the server is told straight after and has the last word. */
    override suspend fun stop(taskId: String, reason: StopReason): CartTask? {
        val t = tasks.value[taskId] ?: return null
        if (!t.status.isTerminal && t.status != TaskStatus.CART_READY) {
            put(t.copy(status = TaskStatus.CANCELLED, checkpoint = null, needsUserAction = null, events = t.events + TaskEvent((t.events.maxOfOrNull { it.seq } ?: 0) + 1, Instant.now().toString(), TaskEventType.HALT, "Stopping…")))
        }
        pollers.remove(taskId)?.cancel()
        scope.launch { stopTask(taskId, reason) }
        return tasks.value[taskId]
    }

    override suspend fun stopAll(reason: StopReason) {
        tasks.value.values.filter { it.status.isActive || it.status == TaskStatus.AWAITING_APPROVAL }.forEach { stop(it.taskId, reason) }
    }

    override suspend fun createHandoff(taskId: String): ApiResult<Handoff> = handoff(taskId)

    /** The server marks the task handed off when it issues the link. */
    override suspend fun markHandedOff(taskId: String) { refresh(taskId) }
}
