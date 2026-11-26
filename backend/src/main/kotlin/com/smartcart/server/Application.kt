package com.smartcart.server

import com.smartcart.server.config.AppConfig
import com.smartcart.server.db.Database
import com.smartcart.server.http.installHttp
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import javax.sql.DataSource

/** Everything a request handler needs; built once at startup, replaced wholesale in tests. */
class Services(val config: AppConfig, val db: DataSource) {
    val metrics = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
}

fun main() {
    val config = AppConfig.fromEnv()
    val pool = Database.pool(config.db)
    Database.migrate(pool)
    val services = Services(config, pool)
    embeddedServer(Netty, port = config.port, host = config.host) {
        module(services)
        monitor.subscribe(ApplicationStopped) { pool.close() }
    }.start(wait = true)
}

fun Application.module(services: Services) {
    installHttp()
    install(MicrometerMetrics) { registry = services.metrics }

    routing {
        // Operational endpoints: not part of the public contract.
        get("/healthz") { call.respondText("""{"status":"ok"}""", ContentType.Application.Json) }
        get("/readyz") {
            val ready = Database.isReady(services.db)
            call.respondText(
                if (ready) """{"status":"ready"}""" else """{"status":"unavailable"}""",
                ContentType.Application.Json,
                if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
            )
        }
        if (services.config.metricsEnabled) {
            get("/metrics") { call.respondText(services.metrics.scrape(), ContentType.parse("text/plain; version=0.0.4")) }
        }
    }
}
