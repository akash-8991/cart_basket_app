package com.smartcart.server

import com.smartcart.server.support.Contract
import com.smartcart.server.support.TestDb
import com.smartcart.server.support.testConfig
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.routing.getAllRoutes
import io.ktor.server.application.pluginOrNull
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Keeps the server and the frozen contract in step:
 *  - every contract operation is either routed or listed in pending-operations.txt (which must reach
 *    zero by slice 3.6 — the Phase 3 exit gate);
 *  - nothing listed as pending is secretly routed;
 *  - no /v1 route exists that the contract doesn't describe.
 */
class RouteCoverageTest {
    private val pending: Set<String> = javaClass.getResource("/pending-operations.txt")!!.readText()
        .lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()

    private fun routedOperations(): Set<String> {
        var routes = emptySet<String>()
        testApplication {
            application {
                module(Services(testConfig(), TestDb.fresh()))
                routes = pluginOrNull(RoutingRoot)!!.getAllRoutes().mapNotNull { r ->
                    // e.g. "/v1/auth/sessions/{sessionId}/(method:DELETE)"
                    Regex("^(.*)/\\(method:([A-Z]+)\\)$").find(r.toString())?.let { m ->
                        // Drop non-path selectors such as "(authenticate user)".
                        "${m.groupValues[2]} ${m.groupValues[1].replace(Regex("/\\([^)]*\\)"), "")}"
                    }
                }.toSet()
            }
            startApplication()
        }
        return routes
    }

    @Test fun routesAndContractAgree() {
        val routed = routedOperations().filter { it.substringAfter(' ').startsWith("/v1/") }.toSet()
        val contract = Contract.operations

        val undocumented = routed - contract
        val missing = contract - routed - pending
        val stalePending = pending.intersect(routed)
        val unknownPending = pending - contract

        val problems = buildList {
            if (undocumented.isNotEmpty()) add("Routes not in the contract: $undocumented")
            if (missing.isNotEmpty()) add("Contract operations with no route and not pending: $missing")
            if (stalePending.isNotEmpty()) add("Implemented but still listed as pending (remove them): $stalePending")
            if (unknownPending.isNotEmpty()) add("Pending entries that aren't contract operations: $unknownPending")
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n"))
        assertTrue(contract.isNotEmpty())
    }
}
