package com.smartcart.data.device

import com.smartcart.data.ApiResult
import com.smartcart.data.CartTaskRepository
import com.smartcart.model.CartTask
import com.smartcart.model.Handoff
import com.smartcart.model.ProviderKey
import com.smartcart.model.StopReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Sends each new task to the phone when an adapter is bound for its provider, and to [simulated] otherwise;
 * after that, every call for the task goes to whichever repository created it.
 */
class RoutingCartTaskRepository(
    private val simulated: CartTaskRepository,
    private val device: DeviceCartTaskRepository,
    private val onDevice: (ProviderKey) -> Boolean,
    scope: CoroutineScope,
) : CartTaskRepository {
    override val activeTask: StateFlow<CartTask?> =
        combine(device.activeTask, simulated.activeTask) { d, s -> d ?: s }.stateIn(scope, SharingStarted.Eagerly, null)

    private fun of(taskId: String): CartTaskRepository = if (device.owns(taskId)) device else simulated

    override suspend fun create(quoteId: String, provider: ProviderKey): ApiResult<CartTask> =
        (if (onDevice(provider)) device else simulated).create(quoteId, provider)

    override fun observe(taskId: String): Flow<CartTask?> = of(taskId).observe(taskId)
    override suspend fun approve(taskId: String, actionIds: List<String>) = of(taskId).approve(taskId, actionIds)
    override suspend fun answerCheckpoint(taskId: String, checkpointId: String, approve: Boolean) = of(taskId).answerCheckpoint(taskId, checkpointId, approve)
    override suspend fun resume(taskId: String) = of(taskId).resume(taskId)
    override suspend fun stop(taskId: String, reason: StopReason) = of(taskId).stop(taskId, reason)

    override suspend fun stopAll(reason: StopReason) {
        device.stopAll(reason)
        simulated.stopAll(reason)
    }

    override suspend fun createHandoff(taskId: String): ApiResult<Handoff> = of(taskId).createHandoff(taskId)
    override suspend fun markHandedOff(taskId: String) = of(taskId).markHandedOff(taskId)
}
