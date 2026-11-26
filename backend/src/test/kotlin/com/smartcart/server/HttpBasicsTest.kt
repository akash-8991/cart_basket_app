package com.smartcart.server

import com.smartcart.server.http.CORRELATION_HEADER
import com.smartcart.server.support.Contract
import com.smartcart.server.support.newCorrelationId
import com.smartcart.server.support.withApp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HttpBasicsTest {
    @Test fun unknownRouteReturnsTheContractErrorEnvelope() = withApp {
        val r = client.get("/v1/does-not-exist")
        assertEquals(HttpStatusCode.NotFound, r.status)
        val p = Contract.componentSchema("ErrorEnvelope").validate(Contract.json.readTree(r.bodyAsText()))
        assertTrue(p.isEmpty(), p.toString())
        assertTrue(r.bodyAsText().contains("\"code\":\"not_found\""))
    }

    @Test fun clientCorrelationIdIsEchoedInHeaderAndErrorBody() = withApp {
        val id = newCorrelationId()
        val r = client.get("/v1/does-not-exist") { header(CORRELATION_HEADER, id) }
        assertEquals(id, r.headers[CORRELATION_HEADER])
        assertTrue(r.bodyAsText().contains("\"correlation_id\":\"$id\""))
    }

    @Test fun missingOrMalformedCorrelationIdIsReplacedWithAUuid() = withApp {
        val r = client.get("/healthz") { header(CORRELATION_HEADER, "not-a-uuid<script>") }
        val id = r.headers[CORRELATION_HEADER]!!
        assertNotEquals("not-a-uuid<script>", id)
        assertTrue(Regex("^[0-9a-f-]{36}$").matches(id))
    }

    @Test fun responsesAreNeverCached() = withApp {
        val r = client.get("/v1/does-not-exist")
        assertEquals("no-store", r.headers[HttpHeaders.CacheControl])
        assertEquals("nosniff", r.headers["X-Content-Type-Options"])
    }

    @Test fun healthAndReadinessReportTheDatabase() = withApp {
        assertEquals(HttpStatusCode.OK, client.get("/healthz").status)
        assertEquals(HttpStatusCode.OK, client.get("/readyz").status)
    }

    @Test fun metricsAreExposedForScraping() = withApp {
        client.get("/healthz")
        val m = client.get("/metrics").bodyAsText()
        assertTrue("ktor_http_server_requests" in m, m.take(300))
    }
}
