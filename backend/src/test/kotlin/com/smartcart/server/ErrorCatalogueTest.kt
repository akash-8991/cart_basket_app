package com.smartcart.server

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.smartcart.server.http.ErrorCode
import com.smartcart.server.support.Contract
import kotlin.test.Test
import kotlin.test.assertEquals

/** The server's ErrorCode enum, contracts/error-codes.yaml and the OpenAPI ErrorCode enum must agree. */
class ErrorCatalogueTest {
    private val catalogue = ObjectMapper(YAMLFactory()).readTree(Contract.contractsDir.resolve("error-codes.yaml")).path("codes")

    @Test fun sameCodesAsTheCatalogue() =
        assertEquals(catalogue.fieldNames().asSequence().toSet(), ErrorCode.entries.map { it.wire }.toSet())

    @Test fun sameHttpStatusesAsTheCatalogue() = ErrorCode.entries.forEach {
        assertEquals(catalogue.path(it.wire).path("http").asInt(), it.status.value, it.wire)
    }

    @Test fun sameCodesAsTheOpenApiEnum() {
        val enum = Contract.openApi.path("components").path("schemas").path("ErrorCode").path("enum").map { it.asText() }.toSet()
        assertEquals(enum, ErrorCode.entries.map { it.wire }.toSet())
    }
}
