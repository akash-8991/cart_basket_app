package com.smartcart.data.device

import com.smartcart.automation.Adapter
import com.smartcart.automation.Driver
import com.smartcart.automation.PackageVerifier
import com.smartcart.model.ProviderKey

/**
 * What the phone offers for running cart tasks itself (Phase 4.2): the accessibility driver while the person
 * has the service turned on, the package verifier, and which provider apps have an adapter.
 */
interface OnDeviceAutomation {
    /** The connected driver, or null while the accessibility service is off. */
    fun driver(): Driver?
    val verifier: PackageVerifier
    /** The adapter that automates [provider] on this phone, or null if none is bound. */
    fun adapterFor(provider: ProviderKey): Adapter?
    /** Brings SmartCart back to the front after a price check opened another app. */
    fun returnToSmartCart() {}
}

/** CR-005: where the phone's price readings go when comparisons are worked out on the server. */
interface DeviceReadingSink {
    suspend fun submitObservation(quoteId: String, observation: com.smartcart.model.PriceObservation): com.smartcart.data.ApiResult<com.smartcart.model.ComparisonQuote>
}

/**
 * Phase 4.5: what a task the phone runs needs from the server. The server holds the task (plan, approval
 * nonce, state) and checks every report against its state machine; its answer is the task as it holds it.
 */
interface TaskServer {
    suspend fun createTask(quoteId: String, provider: com.smartcart.model.ProviderKey): com.smartcart.data.ApiResult<com.smartcart.model.CartTask>
    suspend fun approveTask(taskId: String, actionIds: List<String>, nonce: String): com.smartcart.data.ApiResult<com.smartcart.model.CartTask>
    suspend fun reportProgress(taskId: String, report: com.smartcart.model.TaskProgressReport): com.smartcart.data.ApiResult<com.smartcart.model.CartTask>
    suspend fun answerCheckpoint(taskId: String, checkpointId: String, approve: Boolean): com.smartcart.data.ApiResult<com.smartcart.model.CartTask>
    suspend fun stopTask(taskId: String, reason: com.smartcart.model.StopReason): com.smartcart.data.ApiResult<com.smartcart.model.CartTask>
    suspend fun handoff(taskId: String): com.smartcart.data.ApiResult<com.smartcart.model.Handoff>
    suspend fun getTask(taskId: String): com.smartcart.data.ApiResult<com.smartcart.model.CartTask>
}
