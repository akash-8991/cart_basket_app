package com.smartcart.server

import com.smartcart.server.support.Contract
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 3 exit gate (plan §2): every operation in the frozen contract is implemented and has at least one
 * test that validates a real response against it.
 */
class ExitGateTest {
    @Test fun nothingIsPending() {
        val pending = javaClass.getResource("/pending-operations.txt")!!.readText().lines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals(emptyList(), pending)
    }

    @Test fun everyOperationHasAContractTest() {
        val call = Regex("""assertContract\("(GET|POST|PUT|PATCH|DELETE)",\s*"([^"]+)"\)""")
        val tested = File("src/test/kotlin").walkTopDown().filter { it.extension == "kt" }
            .flatMap { f -> call.findAll(f.readText()).map { "${it.groupValues[1]} ${it.groupValues[2]}" } }.toSet()
        val untested = Contract.operations - tested
        assertTrue(untested.isEmpty(), "Operations without a contract test: ${untested.sorted()}")
    }
}
