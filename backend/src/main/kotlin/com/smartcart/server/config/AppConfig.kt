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
) {
    init {
        if (env == Env.PROD) {
            require(!stubsPhase4) { "SMARTCART_STUBS_PHASE4 must be off in production" }
            require(db.password.isNotBlank()) { "SMARTCART_DB_PASSWORD is required in production" }
        }
    }

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
            )
        }
    }
}
