package com.smartcart.server.config

import com.smartcart.server.Services
import com.smartcart.server.auth.io
import com.smartcart.server.me.ConsentKeys
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

/** Versions of the policy texts the current app shows. Raising one makes the app ask again (CR-003 §3). */
object PolicyVersions {
    const val TERMS = "2026-11"
    const val PRIVACY = "2026-11"
    val CONSENT: String get() = ConsentKeys.CURRENT_VERSION
}

/**
 * The only flags the app may see (CR-002 A). Provider flags and kill switches never leave the server:
 * the app learns provider state from /v1/providers, already resolved.
 */
val CLIENT_FLAGS = listOf(
    "home.category.travel", "home.category.food", "home.category.grocery", "home.category.shopping",
    "chat.ai.enabled", "comparison.enabled", "history.import.enabled", "history.upload.enabled",
)

@Serializable data class PolicyVersionsWire(val terms: String, val privacy: String, val consent: String)
@Serializable data class ClientConfig(val flags: Map<String, Boolean>, val policyVersions: PolicyVersionsWire)

/** Public: the app reads it before sign-in. Nothing user-specific is in it. */
fun Route.configRoutes(s: Services) {
    get("/v1/config") {
        val flags = io { s.flags.all() }
        call.respond(
            ClientConfig(
                flags = CLIENT_FLAGS.associateWith { flags[it]?.enabled ?: false },
                policyVersions = PolicyVersionsWire(PolicyVersions.TERMS, PolicyVersions.PRIVACY, PolicyVersions.CONSENT),
            ),
        )
    }
}
