package com.smartcart.server

import com.smartcart.server.providers.setFlag
import com.smartcart.server.support.assertContract
import com.smartcart.server.support.errorCode
import com.smartcart.server.support.getAuthed
import com.smartcart.server.support.postJson
import com.smartcart.server.support.putJson
import com.smartcart.server.support.register
import com.smartcart.server.support.withApp
import io.ktor.client.HttpClient
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CR-007: the phone reports each app's import; normalised orders reach the server only after review, with
 * consent and the upload flag, in sync mode, and only with the fields the person chose (threat model A-10).
 */
class Cr007Test {
    private val key get() = UUID.randomUUID().toString()
    private suspend fun HttpResponse.obj(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content

    private suspend fun HttpClient.start(t: String, mode: String, fields: String = """["order_date","items","quantity","amounts"]"""): JsonObject {
        setFlag("history.import.enabled", true)
        putJson("/v1/me/consents/history.blinkit", """{"granted":true,"version":"2026-11"}""", t)
        return postJson("/v1/history/import", """{"providers":["blinkit"],"fields":$fields,"upload_mode":"$mode"}""", key, t)
            .assertContract("POST", "/v1/history/import").obj()
    }

    private suspend fun HttpClient.batch(t: String, session: String, body: String) =
        postJson("/v1/history/import/$session/batches", body, key, t).assertContract("POST", "/v1/history/import/{sessionId}/batches")

    private fun ref(salt: String, providerOrderId: String) =
        MessageDigest.getInstance("SHA-256").digest("${salt}blinkit:$providerOrderId".toByteArray()).joinToString("") { "%02x".format(it) }

    private fun order(ref: String, total: Long = 18200, extra: String = "", items: String = """[{"name":"Toned milk 1 L","quantity":2,"amount":{"amount_minor":5600,"currency":"INR"}}]""") =
        """{"source_ref":"$ref","ordered_at":"2026-11-20T03:10:00Z","total":{"amount_minor":$total,"currency":"INR"},"items":$items$extra,"extraction_confidence":"high"}"""

    private fun batch(seq: Int, orders: String = "", reviewed: Boolean = true, outcome: String? = null, status: String = "completed", found: Int = 1) =
        """{"batch_seq":$seq,"provider":"blinkit","status":"$status","orders_found":$found,"confidence":"high","adapter":{"key":"teststore","version":"1"},"reviewed":$reviewed,"orders":[$orders]${outcome?.let { ",\"import_outcome\":\"$it\"" } ?: ""}}"""

    @Test fun aDeviceOnlyImportSendsCountsAndNeverOrders() = withApp {
        val t = client.register().tokens.accessToken
        val s = client.start(t, "device_only")
        assertEquals("running", s.s("status"))
        assertTrue(s["dedup_salt"].toString() == "null", "no salt: nothing is ever uploaded")
        val id = s.s("session_id")
        assertEquals("conflict", client.batch(t, id, batch(1, order("a".repeat(64)))).errorCode(), "orders in device-only mode are refused whole")
        val done = client.batch(t, id, batch(1, found = 9, outcome = "completed")).obj()
        assertEquals("completed", done.s("status"))
        assertEquals("9", done["providers"]!!.jsonArray.single().jsonObject.s("orders_found"))
        assertTrue(done["review_sample"]!!.jsonArray.isEmpty())
        assertEquals("conflict", client.batch(t, id, batch(2)).errorCode(), "a finished import takes nothing more")
    }

    @Test fun ordersNeedReviewTheFlagConsentAndOnlyTheChosenFields() = withApp {
        val t = client.register().tokens.accessToken
        val s = client.start(t, "normalized_upload")
        val id = s.s("session_id")
        val salt = s.s("dedup_salt")
        val one = order(ref(salt, "BLK-1001"))
        assertEquals("conflict", client.batch(t, id, batch(1, one, reviewed = false)).errorCode(), "not before the person has reviewed")
        assertEquals("policy_blocked", client.batch(t, id, batch(1, one)).errorCode(), "upload is off until the privacy review (R-05)")
        client.setFlag("history.upload.enabled", true)
        assertEquals("validation_failed", client.batch(t, id, batch(1, order(ref(salt, "BLK-1001"), extra = ",\"status\":\"DELIVERED\""))).errorCode(), "status wasn't chosen")
        assertEquals("validation_failed", client.batch(t, id, batch(1, order(ref(salt, "BLK-1001"), extra = ",\"location_label\":\"Home\""))).errorCode(), "location wasn't chosen")

        val r = client.batch(t, id, batch(1, one, status = "running")).obj()
        assertEquals("running", r.s("status"))
        val listed = client.getAuthed("/v1/history/orders", t).assertContract("GET", "/v1/history/orders").obj()["orders"]!!.jsonArray.map { it.jsonObject }
        assertEquals(1, listed.size, "only what was uploaded, nothing invented")
        assertEquals("UNKNOWN", listed.single().s("status"), "a status that wasn't chosen stays unknown")
        assertFalse("BLK-1001" in client.getAuthed("/v1/history/orders", t).bodyAsText(), "the provider's order ID never arrives")

        client.putJson("/v1/me/consents/history.blinkit", """{"granted":false,"version":"2026-11"}""", t)
        assertEquals("consent_required", client.batch(t, id, batch(2, order(ref(salt, "BLK-1002")))).errorCode(), "nothing more once consent is withdrawn")
    }

    @Test fun duplicatesAreStoredOnceAndTotalsAreChecked() = withApp {
        val t = client.register().tokens.accessToken
        client.setFlag("history.upload.enabled", true)
        val s = client.start(t, "normalized_upload")
        val id = s.s("session_id")
        val salt = s.s("dedup_salt")
        client.batch(t, id, batch(1, order(ref(salt, "BLK-1")), status = "running"))
        client.batch(t, id, batch(2, order(ref(salt, "BLK-1"), total = 19000) + "," + order(ref(salt, "BLK-2"), total = 1000), status = "running"))
        val listed = client.getAuthed("/v1/history/orders", t).obj()["orders"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, listed.size, "the same order imported twice is one order")
        assertTrue(listed.any { it["total"]!!.jsonObject.s("amount_minor") == "19000" }, "updated, not duplicated")
        val impossible = listed.single { it["total"]!!.jsonObject.s("amount_minor") == "1000" }
        assertEquals("low", impossible.s("extraction_confidence"), "items worth more than the total can't be right")
    }

    @Test fun batchesComeInOrderAndOnlyFromTheOwner() = withApp {
        val t = client.register().tokens.accessToken
        val id = client.start(t, "device_only").s("session_id")
        assertEquals("conflict", client.batch(t, id, batch(2)).errorCode(), "batch 1 comes first")
        val other = client.register("ravi.s", "ravi@example.com", "dev-9").tokens.accessToken
        assertEquals("not_found", client.batch(other, id, batch(1)).errorCode(), "someone else's import (A-15)")
        assertEquals("validation_failed", client.batch(t, id, batch(1, status = "exploded")).errorCode())
    }
}
