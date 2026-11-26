package com.smartcart.server.support

import com.smartcart.server.Services
import com.smartcart.server.config.AppConfig
import com.smartcart.server.config.DbConfig
import com.smartcart.server.config.Env
import com.smartcart.server.db.Database
import com.smartcart.server.module
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.util.UUID
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import com.smartcart.server.mail.MailTemplate
import com.smartcart.server.mail.Mailer
import com.smartcart.server.security.InMemoryIdempotencyStore
import com.smartcart.server.security.InMemoryRateLimiter
import javax.sql.DataSource
import kotlin.test.fail

/** One embedded PostgreSQL 16 per test JVM; each test gets its own freshly migrated database. */
object TestDb {
    private val pg: EmbeddedPostgres by lazy { EmbeddedPostgres.builder().start() }

    fun fresh(): DataSource {
        val name = "t_" + UUID.randomUUID().toString().replace("-", "").take(16)
        pg.postgresDatabase.connection.use { it.createStatement().execute("CREATE DATABASE $name") }
        val ds = pg.getDatabase("postgres", name)
        Database.migrate(ds)
        return ds
    }
}

fun testConfig(stubsPhase4: Boolean = true) = AppConfig(
    env = Env.TEST,
    host = "127.0.0.1",
    port = 0,
    db = DbConfig("jdbc:unused", "unused", "unused"),
    stubsPhase4 = stubsPhase4,
    adminToken = ADMIN_TOKEN,
    requireProviderApproval = true,
    flagCacheSeconds = 0,
)

const val ADMIN_TOKEN = "test-admin-token-0123456789abcdef0123"


/** A clock tests can move forward. */
class MutableClock(var now: Instant = Instant.parse("2026-11-26T05:00:00Z")) : Clock() {
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId?): Clock = this
    fun advance(d: Duration) { now = now.plus(d) }
}

/** Captures mail instead of sending it. */
class RecordingMailer : Mailer {
    data class Sent(val to: String, val template: MailTemplate, val params: Map<String, String>)
    val sent = java.util.concurrent.CopyOnWriteArrayList<Sent>()
    override fun send(to: String, template: MailTemplate, params: Map<String, String>) { sent += Sent(to, template, params) }
}

fun testServices(clock: MutableClock = MutableClock(), mailer: Mailer = RecordingMailer()) =
    Services(testConfig(), TestDb.fresh(), clock = clock, limiter = InMemoryRateLimiter(clock), idempotency = InMemoryIdempotencyStore(clock), mailer = mailer)

/** Runs [block] against the real application module wired to a fresh database. */
fun withApp(services: Services = testServices(), block: suspend ApplicationTestBuilder.(Services) -> Unit) =
    testApplication {
        application { module(services) }
        block(services)
    }

/** Fails the test unless [this] response conforms to the contract for [method] [pathTemplate]. */
suspend fun HttpResponse.assertContract(method: String, pathTemplate: String): HttpResponse {
    val body = bodyAsText()
    val problems = Contract.responseProblems(method, pathTemplate, status.value, headers[HttpHeaders.ContentType], body)
    if (problems.isNotEmpty()) fail("$method $pathTemplate → ${status.value} breaks the contract:\n  " + problems.joinToString("\n  ") + "\nbody: $body")
    return this
}

fun newCorrelationId(): String = UUID.randomUUID().toString()

/**
 * For error statuses the contract forgets to document on an operation (logged as contract gaps in
 * docs/phase3/PLAN.md): the body must still be a valid ErrorEnvelope.
 */
suspend fun HttpResponse.assertErrorEnvelope(): HttpResponse {
    val problems = Contract.componentSchema("ErrorEnvelope").validate(Contract.json.readTree(bodyAsText()))
    if (problems.isNotEmpty()) fail("not an ErrorEnvelope: $problems\nbody: ${bodyAsText()}")
    return this
}
