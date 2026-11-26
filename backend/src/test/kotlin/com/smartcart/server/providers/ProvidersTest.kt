package com.smartcart.server.providers

import com.smartcart.server.config.AppConfig
import com.smartcart.server.config.DbConfig
import com.smartcart.server.config.Env
import com.smartcart.server.flags.Flags
import com.smartcart.server.support.ADMIN_TOKEN
import com.smartcart.server.support.Contract
import com.smartcart.server.support.MutableClock
import com.smartcart.server.support.TestDb
import com.smartcart.server.support.assertContract
import com.smartcart.server.support.getAuthed
import com.smartcart.server.support.putJson
import com.smartcart.server.support.register
import com.smartcart.server.support.withApp
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

suspend fun HttpClient.admin(method: String, path: String, body: String, token: String = ADMIN_TOKEN, operator: String? = "ops.akash"): HttpResponse {
    val build: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
        bearerAuth(token)
        operator?.let { header("X-Operator", it) }
        contentType(ContentType.Application.Json)
        setBody(body)
    }
    return if (method == "PUT") put(path, build) else delete(path, build)
}

suspend fun HttpClient.setFlag(key: String, on: Boolean) =
    admin("PUT", "/admin/flags/$key", """{"enabled":$on,"reason":"test"}""").also { check(it.status.value == 200) { it.bodyAsText() } }

suspend fun HttpClient.approve(key: String) =
    admin("PUT", "/admin/providers/$key/approval", """{"adapter_version":"1.2.0","approved_by":"product+security","record_url":"https://example.invalid/approvals/$key","reason":"approval complete"}""")

class ProvidersTest {
    private suspend fun HttpClient.statusOf(token: String, key: String): String {
        val body = getAuthed("/v1/providers", token).assertContract("GET", "/v1/providers").bodyAsText()
        return Regex("\"key\":\"$key\".*?\"status\":\"([a-z_]+)\"").find(body)!!.groupValues[1]
    }

    @Test fun registryMatchesTheFrozenFixture() {
        val fixture = Contract.json.readTree(Contract.contractsDir.resolve("fixtures/providers/capabilities.json")).path("providers")
        assertEquals(fixture.map { it.path("key").asText() }, ProviderRegistry.all.map { it.key })
        fixture.forEach { f ->
            val p = ProviderRegistry.find(f.path("key").asText())!!
            assertEquals(f.path("display_name").asText(), p.displayName)
            assertEquals(f.path("category").asText(), p.category)
            val c = f.path("capabilities")
            assertEquals(Capabilities(c.path("cart_automation").asText(), c.path("history_import").asText(), c.path("deep_link").asText(), c.path("comparison").asText()), p.capabilities)
        }
    }

    @Test fun everyProviderStartsDisabled() = withApp {
        val t = client.register().tokens.accessToken
        val body = client.getAuthed("/v1/providers", t).assertContract("GET", "/v1/providers").bodyAsText()
        assertEquals(11, Regex("\"status\":\"disabled\"").findAll(body).count(), body)
        assertEquals(11, Regex("\"installed\":null").findAll(body).count())
        assertEquals(11, Regex("\"adapter_version\":null").findAll(body).count())
    }

    @Test fun enablingWithoutAnApprovalRecordIsOnlyPendingApproval() = withApp {
        val t = client.register().tokens.accessToken
        client.setFlag("provider.blinkit.enabled", true)
        assertEquals("pending_approval", client.statusOf(t, "blinkit"))
        client.approve("blinkit")
        assertEquals("enabled", client.statusOf(t, "blinkit"))
        assertTrue("\"key\":\"blinkit\"" in client.getAuthed("/v1/providers", t).bodyAsText().let { Regex("\\{\"key\":\"blinkit\".*?\"adapter_version\":\"1.2.0\",\"hidden\":false\\}").find(it)!!.value })

        assertEquals(204, client.admin("DELETE", "/admin/providers/blinkit/approval", """{"reason":"partner terms changed"}""").status.value)
        assertEquals("pending_approval", client.statusOf(t, "blinkit"))
    }

    @Test fun killSwitchOverridesEverythingImmediately() = withApp { s ->
        val t = client.register().tokens.accessToken
        client.setFlag("provider.zomato.enabled", true)
        client.approve("zomato")
        assertTrue(s.providers.isUsable("zomato"))
        client.setFlag("provider.zomato.kill", true)
        assertEquals("kill_switched", client.statusOf(t, "zomato"))
        assertEquals(false, s.providers.isUsable("zomato"))
        client.setFlag("provider.zomato.kill", false)
        assertEquals("enabled", client.statusOf(t, "zomato"))
    }

    @Test fun consentIsPerUser() = withApp {
        val priya = client.register().tokens.accessToken
        val ravi = client.register(userId = "ravi.s", email = "ravi@example.com", deviceId = "dev-9").tokens.accessToken
        client.putJson("/v1/me/consents/automation.blinkit", """{"granted":true,"version":"2026-11"}""", priya)
        fun consent(body: String) = Regex("\\{\"key\":\"blinkit\".*?\"consent\":\\{\"automation\":(true|false)").find(body)!!.groupValues[1]
        assertEquals("true", consent(client.getAuthed("/v1/providers", priya).bodyAsText()))
        assertEquals("false", consent(client.getAuthed("/v1/providers", ravi).bodyAsText()))
    }

    @Test fun flagChangesReachOtherInstancesWithinTheCacheWindow() {
        val db = TestDb.fresh()
        val clock = MutableClock()
        val a = Flags(db, clock, Duration.ofSeconds(5)).also { it.seed(Flags.catalogue()) }
        val b = Flags(db, clock, Duration.ofSeconds(5))
        assertEquals(false, a.isOn("provider.uber.kill"))
        b.set("provider.uber.kill", true, "ops", "incident")
        assertEquals(false, a.isOn("provider.uber.kill"), "instance A still has its cached value")
        clock.advance(Duration.ofSeconds(5))
        assertEquals(true, a.isOn("provider.uber.kill"))
    }

    @Test fun seedingNeverOverwritesAnOperatorsChange() {
        val db = TestDb.fresh()
        val f = Flags(db, MutableClock(), Duration.ZERO).also { it.seed(Flags.catalogue()) }
        f.set("chat.ai.enabled", false, "ops", "model incident")
        f.seed(Flags.catalogue())
        assertEquals(false, f.isOn("chat.ai.enabled"))
        assertEquals(false, f.isOn("no.such.flag"), "unknown flags are off")
        assertTrue(f.exists("provider.zepto.kill"), "a kill switch exists for every provider")
        assertTrue(Flags.isHidden("provider.zepto.enabled"))
    }

    @Test fun productionCannotWaiveProviderApproval() {
        assertFailsWith<IllegalArgumentException> {
            AppConfig(Env.PROD, "0.0.0.0", 8080, DbConfig("jdbc:x", "u", "p"), stubsPhase4 = false, secretKey = "k", jwtPrivateKey = "a", jwtPublicKey = "b", redisUrl = "redis://r", requireProviderApproval = false)
        }
    }
}
