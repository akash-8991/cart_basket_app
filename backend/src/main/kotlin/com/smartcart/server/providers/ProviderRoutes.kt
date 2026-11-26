package com.smartcart.server.providers

import com.smartcart.server.Services
import com.smartcart.server.auth.AUTH_USER
import com.smartcart.server.auth.io
import com.smartcart.server.auth.user
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

@Serializable data class ProviderCapabilities(val cartAutomation: String, val historyImport: String, val deepLink: String, val comparison: String)
@Serializable data class ProviderConsent(val automation: Boolean, val history: Boolean)
@Serializable data class Provider(
    val key: String,
    val displayName: String,
    val category: String,
    val capabilities: ProviderCapabilities,
    val status: String,
    /** Filled in by the app from PackageManager; the server can't know. */
    val installed: Boolean?,
    val consent: ProviderConsent,
    val adapterVersion: String?,
    /** CR-002 F: registry-only provider the app must not show. */
    val hidden: Boolean,
)
@Serializable data class ProviderList(val providers: List<Provider>)

fun ProviderView.wire() = Provider(
    key = def.key,
    displayName = def.displayName,
    category = def.category,
    capabilities = def.capabilities.let { ProviderCapabilities(it.cartAutomation, it.historyImport, it.deepLink, it.comparison) },
    status = status,
    installed = null,
    consent = ProviderConsent(automationConsent, historyConsent),
    adapterVersion = adapterVersion,
    hidden = com.smartcart.server.flags.Flags.isHidden("provider.${def.key}.enabled"),
)

fun Route.providerRoutes(s: Services) = authenticate(AUTH_USER) {
    get("/v1/providers") {
        call.respond(ProviderList(io { s.providers.list(call.user().userId) }.map { it.wire() }))
    }
}
