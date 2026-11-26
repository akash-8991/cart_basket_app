package com.smartcart.server

import com.smartcart.server.support.Contract
import kotlin.test.Test
import kotlin.test.assertTrue

/** The contract checker is only worth something if it rejects bad responses. */
class ContractValidatorTest {
    private val fixture = Contract.contractsDir.resolve("fixtures/auth/login.success.json").readText()

    private fun problems(body: String, status: Int = 200) =
        Contract.responseProblems("POST", "/v1/auth/login", status, "application/json", body)

    @Test fun acceptsTheFrozenFixture() = assertTrue(problems(fixture).isEmpty(), problems(fixture).toString())

    @Test fun rejectsAMissingRequiredField() =
        assertTrue(problems(fixture.replace("\"session_id\": \"ses_01\",", "")).any { "session_id" in it })

    @Test fun rejectsAnUndocumentedField() =
        assertTrue(problems(fixture.replace("{", "{\"debug\": true,")).isNotEmpty())

    @Test fun rejectsAWrongType() =
        assertTrue(problems(fixture.replace("900", "\"900\"")).isNotEmpty())

    @Test fun rejectsABadDateTime() =
        assertTrue(problems(fixture.replace("2026-12-22T05:00:00Z", "next tuesday")).isNotEmpty())

    @Test fun rejectsAnUndocumentedStatus() =
        assertTrue(problems(fixture, status = 418).any { "does not document status 418" in it })

    @Test fun rejectsNonJsonContentType() =
        assertTrue(Contract.responseProblems("POST", "/v1/auth/login", 200, "text/html", fixture).isNotEmpty())

    @Test fun errorEnvelopeFixturesConform() {
        val body = Contract.contractsDir.resolve("fixtures/auth/login.invalid-credentials.json").readText()
        val p = Contract.responseProblems("POST", "/v1/auth/login", 401, "application/json", body)
        assertTrue(p.isEmpty(), p.toString())
    }
}
