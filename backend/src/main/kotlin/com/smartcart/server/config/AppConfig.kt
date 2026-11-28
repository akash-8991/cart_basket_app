package com.smartcart.server.config

enum class Env { DEV, TEST, PROD }

data class DbConfig(val url: String, val user: String, val password: String, val maxPoolSize: Int = 10)

/**
 * Runtime configuration. Everything comes from environment variables (12-factor); secrets are never
 * read from files in the repository. Production refuses to start with development defaults.
 */
data class AppConfig(
    val env: Env,
    val host: String,
    val port: Int,
    val db: DbConfig,
    /** Fixture-backed handlers for Phase 4 endpoints (D-020). Never on in production. */
    val stubsPhase4: Boolean,
    val metricsEnabled: Boolean = true,
    /** Base64, ≥ 32 bytes. Dev/test generate a random one per process when absent. */
    val secretKey: String? = null,
    /** Base64 DER ES256 keys for access tokens. Dev/test generate a pair per process when absent. */
    val jwtPrivateKey: String? = null,
    val jwtPublicKey: String? = null,
    /** e.g. redis://localhost:6379. Absent → in-memory limits (single instance, dev/test only). */
    val redisUrl: String? = null,
    /** Bearer token for the operator API under /admin (≥ 32 chars). Unset → the admin API doesn't exist. */
    val adminToken: String? = null,
    /** Providers need an approval record to be `enabled` (provider-matrix.md). Off only for local development. */
    val requireProviderApproval: Boolean = true,
    /** How long an instance may serve a cached flag value. */
    val flagCacheSeconds: Long = 5,
    /** Live Claude calls (D-019). Off until Phase 4; the key is read from ANTHROPIC_API_KEY by the SDK. */
    val aiLiveCalls: Boolean = false,
    /**
     * CR-006: Android App Links. "package:SHA256FP[,SHA256FP…]" for the app whose verified https links open
     * handoffs; served at /.well-known/assetlinks.json. Unset until the team has a domain and a release key.
     */
    val appLinks: String? = null,
) {
    init {
        adminToken?.let { require(it.length >= 32) { "SMARTCART_ADMIN_TOKEN must be at least 32 characters" } }
        if (env == Env.PROD) {
            require(!stubsPhase4) { "SMARTCART_STUBS_PHASE4 must be off in production" }
            require(db.password.isNotBlank()) { "SMARTCART_DB_PASSWORD is required in production" }
            require(secretKey != null) { "SMARTCART_SECRET_KEY is required in production" }
            require(jwtPrivateKey != null && jwtPublicKey != null) { "SMARTCART_JWT_PRIVATE_KEY and SMARTCART_JWT_PUBLIC_KEY are required in production" }
            require(redisUrl != null) { "SMARTCART_REDIS_URL is required in production" }
            require(requireProviderApproval) { "provider approval cannot be waived in production" }
        }
    }

    override fun toString() = "AppConfig(env=$env, host=$host, port=$port, db=${db.url}, stubsPhase4=$stubsPhase4, redis=${redisUrl != null}, admin=${adminToken != null}, requireProviderApproval=$requireProviderApproval, aiLive=$aiLiveCalls)"


    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): AppConfig {
            val mode = Env.valueOf((env["SMARTCART_ENV"] ?: "dev").uppercase())
            return AppConfig(
                env = mode,
                host = env["SMARTCART_HOST"] ?: "127.0.0.1",
                port = env["SMARTCART_PORT"]?.toInt() ?: 8080,
                db = DbConfig(
                    url = env["SMARTCART_DB_URL"] ?: "jdbc:postgresql://localhost:5432/smartcart",
                    user = env["SMARTCART_DB_USER"] ?: "smartcart",
                    password = env["SMARTCART_DB_PASSWORD"] ?: "",
                    maxPoolSize = env["SMARTCART_DB_POOL"]?.toInt() ?: 10,
                ),
                stubsPhase4 = env["SMARTCART_STUBS_PHASE4"]?.toBooleanStrict() ?: (mode == Env.DEV),
                metricsEnabled = env["SMARTCART_METRICS"]?.toBooleanStrict() ?: true,
                secretKey = env["SMARTCART_SECRET_KEY"],
                jwtPrivateKey = env["SMARTCART_JWT_PRIVATE_KEY"],
                jwtPublicKey = env["SMARTCART_JWT_PUBLIC_KEY"],
                redisUrl = env["SMARTCART_REDIS_URL"],
                adminToken = env["SMARTCART_ADMIN_TOKEN"],
                requireProviderApproval = env["SMARTCART_REQUIRE_PROVIDER_APPROVAL"]?.toBooleanStrict() ?: (mode != Env.DEV),
                flagCacheSeconds = env["SMARTCART_FLAG_CACHE_SECONDS"]?.toLong() ?: 5,
                aiLiveCalls = env["SMARTCART_AI_LIVE"]?.toBooleanStrict() ?: false,
                appLinks = env["SMARTCART_APP_LINKS"]?.takeIf { it.isNotBlank() },
            )
        }
    }
}
