package com.smartcart.data.fake

import com.smartcart.data.ApiResult
import com.smartcart.data.Fixtures
import com.smartcart.data.clientError
import com.smartcart.data.mock.MockControl
import com.smartcart.model.ErrorCode

abstract class FakeBase(protected val fixtures: Fixtures, protected val mock: MockControl) {
    protected val cfg get() = mock.current

    /** Simulates a network round trip: latency, offline, then the scenario's response. */
    protected suspend fun <T> call(latencyFactor: Double = 1.0, block: () -> ApiResult<T>): ApiResult<T> {
        mock.latency(latencyFactor)
        if (cfg.offline) return clientError(ErrorCode.OFFLINE, "You're offline.")
        return block()
    }

    protected fun serviceError() = fixtures.error("errors/service-unavailable.json")
}
