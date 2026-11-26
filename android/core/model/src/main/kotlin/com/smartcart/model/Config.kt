package com.smartcart.model

import kotlinx.serialization.Serializable

/** GET /v1/config (CR-002 A): client-visible flags and the current policy text versions. */
@Serializable
data class ClientConfig(val flags: Map<String, Boolean>, val policyVersions: PolicyVersions)

@Serializable
data class PolicyVersions(val terms: String, val privacy: String, val consent: String)
