package com.smartcart.data.http

import com.smartcart.data.ApiResult
import com.smartcart.data.StoredSession
import com.smartcart.data.TokenStore
import com.smartcart.model.AccountState
import com.smartcart.model.DeviceInfo
import com.smartcart.model.ErrorCode
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class MemoryTokenStore(var stored: StoredSession? = null) : TokenStore {
    override fun save(userId: String, refreshToken: String, remember: Boolean) { stored = StoredSession(userId, refreshToken, remember) }
    override fun read() = stored
    override fun clear() { stored = null }
}

/** A tiny stand-in backend: scripted responses, every request recorded. */
class FakeBackend : AutoCloseable {
    data class Seen(val method: String, val path: String, val headers: Map<String, String>, val body: String)
    val seen = CopyOnWriteArrayList<Seen>()
    var handler: (Seen) -> Pair<Int, String> = { 404 to "" }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex: HttpExchange ->
            val s = Seen(ex.requestMethod, ex.requestURI.path, ex.requestHeaders.mapValues { it.value.first() }.mapKeys { it.key.lowercase() }, ex.requestBody.readAllBytes().decodeToString())
            seen += s
            val (code, body) = synchronized(this@FakeBackend) { handler(s) }
            val bytes = body.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
        }
        executor = java.util.concurrent.Executors.newFixedThreadPool(8)
        start()
    }
    val url = "http://127.0.0.1:${server.address.port}"
    override fun close() = server.stop(0)
}

private fun err(code: String) = """{"error":{"code":"$code","message":"m","correlation_id":"c","retry_after_seconds":null}}"""
private const val TOKENS = """{"access_token":"at-2","refresh_token":"rt-2","access_expires_in_seconds":900}"""
private const val PROFILE = """{"user_id":"priya.k","email_masked":"p***@e***.com","email_verified":false,"privacy_mode":"device_only","created_at":"2026-11-26T05:00:00Z"}"""

class ApiClientTest {
    private val backend = FakeBackend()
    private val store = MemoryTokenStore(StoredSession("priya.k", "rt-1", remembered = true))
    private val api = ApiClient(backend.url)
    private val auth = HttpAuthRepository(api, store) { DeviceInfo(deviceId = "dev-1", appVersion = "1.0.0") }
    private val account = HttpAccountRepository(api, auth)

    @After fun tearDown() = backend.close()

    @Test fun everyCallCarriesACorrelationIdAndOnlyIdempotentOnesAKey() = runBlocking {
        backend.handler = { if (it.path == "/v1/me") 200 to PROFILE else 202 to """{"request_id":"exp_1","status":"queued","message":"m","sla_days":7}""" }
        account.profile()
        account.export("pw")
        val (get, post) = backend.seen
        assertTrue(Regex("^[0-9a-f-]{36}$").matches(get.headers.getValue("x-correlation-id")))
        assertNull(get.headers["idempotency-key"])
        assertNotNull(post.headers["idempotency-key"])
        assertEquals("""{"password":"pw"}""", post.body)
    }

    @Test fun anExpiredSessionRefreshesOnceAndRetriesWithTheSameIdempotencyKey() = runBlocking {
        backend.handler = { s ->
            when {
                s.path == "/v1/auth/refresh" -> 200 to TOKENS
                s.headers["authorization"] == "Bearer at-2" -> 202 to """{"request_id":"exp_1","status":"queued","message":"m","sla_days":7}"""
                else -> 401 to err("session_expired")
            }
        }
        val r = account.export("pw")
        assertTrue(r is ApiResult.Success)
        val exports = backend.seen.filter { it.path == "/v1/me/data/export" }
        assertEquals(2, exports.size)
        assertEquals(exports[0].headers["idempotency-key"], exports[1].headers["idempotency-key"])
        assertEquals("""{"refresh_token":"rt-1","device_id":"dev-1"}""", backend.seen.single { it.path == "/v1/auth/refresh" }.body)
        assertEquals("the rotated refresh token replaces the spent one", "rt-2", store.stored!!.refreshToken)
    }

    @Test fun concurrentExpiredCallsShareOneRefresh() = runBlocking {
        val refreshes = AtomicInteger()
        backend.handler = { s ->
            when {
                s.path == "/v1/auth/refresh" -> { refreshes.incrementAndGet(); Thread.sleep(100); 200 to TOKENS }
                s.headers["authorization"] == "Bearer at-2" -> 200 to PROFILE
                else -> 401 to err("session_expired")
            }
        }
        (1..5).map { async { account.profile() } }.awaitAll().forEach { assertTrue(it is ApiResult.Success) }
        assertEquals(1, refreshes.get())
    }

    @Test fun aFailedRefreshSignsTheUserOut() = runBlocking {
        backend.handler = { s -> if (s.path == "/v1/auth/refresh") 401 to err("session_expired") else 401 to err("session_expired") }
        val r = account.profile()
        assertEquals(ErrorCode.SESSION_EXPIRED, (r as ApiResult.Failure).error.code)
        assertEquals(AccountState.SignedOut, auth.state.value)
        assertNull(store.stored)
    }

    @Test fun serverErrorsKeepTheirCodeAndNetworkFailuresBecomeServiceUnavailable() = runBlocking {
        backend.handler = { 409 to """{"error":{"code":"user_id_unavailable","message":"taken","correlation_id":"c","retry_after_seconds":null,"field_errors":[{"field":"user_id","code":"user_id_unavailable"}]}}""" }
        val taken = auth.register("priya.k", "p@example.com", "correct horse battery") as ApiResult.Failure
        assertEquals(ErrorCode.USER_ID_UNAVAILABLE, taken.error.code)
        assertEquals("user_id", taken.error.fieldErrors.single().field)

        backend.close()
        val down = account.profile() as ApiResult.Failure
        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, down.error.code)
    }

    @Test fun logoutAlwaysEndsTheSessionOnTheDevice() = runBlocking {
        backend.handler = { 500 to "" }
        auth.logout()
        assertEquals(AccountState.SignedOut, auth.state.value)
        assertNull(store.stored)
    }
}
