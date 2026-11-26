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
)

/** Runs [block] against the real application module wired to a fresh database. */
fun withApp(services: Services = Services(testConfig(), TestDb.fresh()), block: suspend ApplicationTestBuilder.(Services) -> Unit) =
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
