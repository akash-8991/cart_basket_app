package com.smartcart.server

import com.smartcart.server.config.AppConfig
import com.smartcart.server.config.Env
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
import com.smartcart.server.audit.Audit
import com.smartcart.server.audit.IpHasher
import com.smartcart.server.auth.AuthRepository
import com.smartcart.server.auth.AuthService
import com.smartcart.server.auth.authRoutes
import com.smartcart.server.auth.installAuthentication
import com.smartcart.server.mail.Mailer
import com.smartcart.server.mail.OutboxMailer
import com.smartcart.server.security.IdempotencyStore
import com.smartcart.server.security.InMemoryIdempotencyStore
import com.smartcart.server.security.InMemoryRateLimiter
import com.smartcart.server.security.Passwords
import com.smartcart.server.security.RateLimiter
import com.smartcart.server.security.RedisIdempotencyStore
import com.smartcart.server.security.RedisRateLimiter
import com.smartcart.server.security.Secrets
import com.smartcart.server.security.SigningKeys
import com.smartcart.server.security.Tokens
import java.time.Clock
import java.time.Duration
import com.smartcart.server.admin.adminRoutes
import com.smartcart.server.flags.Flags
import com.smartcart.server.providers.ProviderApprovals
import com.smartcart.server.providers.ProviderService
import com.smartcart.server.providers.providerRoutes
import com.smartcart.server.me.ActivityLog
import com.smartcart.server.me.DataRequestProcessor
import com.smartcart.server.me.MeRepository
import com.smartcart.server.me.MeService
import com.smartcart.server.me.meRoutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Base64

/** Everything a request handler needs; built once at startup, replaced piecemeal in tests. */
class Services(
    val config: AppConfig,
    val db: DataSource,
    val clock: Clock = Clock.systemUTC(),
    val limiter: RateLimiter = config.redisUrl?.let(::RedisRateLimiter) ?: InMemoryRateLimiter(clock),
    val idempotency: IdempotencyStore = config.redisUrl?.let(::RedisIdempotencyStore) ?: InMemoryIdempotencyStore(clock),
    val mailer: Mailer = OutboxMailer(db),
    val passwords: Passwords = Passwords(),
) {
    init {
        // The outbox stores recovery codes in the database: a development convenience only.
        require(config.env != Env.PROD || mailer !is OutboxMailer) { "configure a real Mailer for production" }
    }

    val metrics = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    val secrets: Secrets = config.secretKey?.let { Secrets(Base64.getDecoder().decode(it)) } ?: Secrets.random()
    val ipHasher = IpHasher(secrets.subKey("ip"))
    val tokens = Tokens(
        config.jwtPrivateKey?.let { SigningKeys.fromBase64(it, config.jwtPublicKey!!) } ?: SigningKeys.generate(),
        clock,
    )
    val audit = Audit(db)
    private val meRepo = MeRepository(db)
    val flags = Flags(db, clock, Duration.ofSeconds(config.flagCacheSeconds)).also { it.seed(Flags.catalogue()) }
    val providerApprovals = ProviderApprovals(db)
    val providers = ProviderService(flags, providerApprovals, meRepo, config.requireProviderApproval)
    val activity = ActivityLog(meRepo)
    val auth = AuthService(AuthRepository(db), passwords, tokens, limiter, secrets, mailer, audit, clock, activity)
    val me = MeService(meRepo, auth, activity, limiter, audit, clock)
    val dataRequests = DataRequestProcessor(meRepo, mailer, activity, audit, clock)
}

fun main() {
    val config = AppConfig.fromEnv()
    val pool = Database.pool(config.db)
    Database.migrate(pool)
    val services = Services(config, pool)
    embeddedServer(Netty, port = config.port, host = config.host) {
        module(services)
        // Background worker for export/deletion requests.
        val worker = launch(Dispatchers.IO) {
            while (isActive) {
                runCatching { services.dataRequests.processPending() }
                delay(30_000)
            }
        }
        monitor.subscribe(ApplicationStopped) { worker.cancel(); pool.close() }
    }.start(wait = true)
}

fun Application.module(services: Services) {
    installHttp()
    install(MicrometerMetrics) { registry = services.metrics }
    installAuthentication(services)

    routing {
        authRoutes(services)
        meRoutes(services)
        providerRoutes(services)
        adminRoutes(services)

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
