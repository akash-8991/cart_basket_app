package com.smartcart.server.chat

import com.smartcart.server.providers.setFlag
import com.smartcart.server.support.MutableClock
import com.smartcart.server.support.assertContract
import com.smartcart.server.support.assertErrorEnvelope
import com.smartcart.server.support.errorCode
import com.smartcart.server.support.getAuthed
import com.smartcart.server.support.postJson
import com.smartcart.server.support.putJson
import com.smartcart.server.support.register
import com.smartcart.server.support.testServices
import com.smartcart.server.support.withApp
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val key get() = UUID.randomUUID().toString()

suspend fun HttpClient.newChat(token: String, category: String = "grocery"): String {
    val r = postJson("/v1/chat/sessions", """{"category":"$category"}""", key, token).assertContract("POST", "/v1/chat/sessions")
    check(r.status.value == 201) { r.bodyAsText() }
    return Regex("\"session_id\":\"([^\"]+)\"").find(r.bodyAsText())!!.groupValues[1]
}

fun messageBody(sessionId: String, text: String, answer: Pair<String, String>? = null, installed: String = "\"blinkit\",\"bigbasket\"") =
    """{"session_id":"$sessionId","text":${kotlinx.serialization.json.JsonPrimitive(text)},""" +
        (answer?.let { """"clarification_answer":{"clarification_id":"${it.first}","option_id":"${it.second}"},""" } ?: "") +
        """"context":{"installed_providers":[$installed],"locale":"en-IN","currency":"INR"}}"""

suspend fun HttpClient.say(token: String, sessionId: String, text: String, answer: Pair<String, String>? = null): HttpResponse =
    postJson("/v1/chat/messages", messageBody(sessionId, text, answer), key, token).assertContract("POST", "/v1/chat/messages")

suspend fun HttpClient.allowAi(token: String) =
    putJson("/v1/me/consents/ai_processing", """{"granted":true,"version":"2026-11"}""", token)

class ChatTest {
    @Test fun sessionsCanBeCreatedListedReadAndDeleted() = withApp {
        val t = client.register().tokens.accessToken
        val id = client.newChat(t)
        val list = client.getAuthed("/v1/chat/sessions", t).assertContract("GET", "/v1/chat/sessions").bodyAsText()
        assertTrue("\"session_id\":\"$id\",\"category\":\"grocery\",\"title\":\"New grocery list\"" in list, list)
        client.getAuthed("/v1/chat/sessions/$id", t).assertContract("GET", "/v1/chat/sessions/{sessionId}")
        assertEquals(204, client.delete("/v1/chat/sessions/$id") { bearerAuth(t) }.assertContract("DELETE", "/v1/chat/sessions/{sessionId}").status.value)
        assertEquals(404, client.getAuthed("/v1/chat/sessions/$id", t).assertContract("GET", "/v1/chat/sessions/{sessionId}").status.value)
    }

    @Test fun creatingASessionIsIdempotent() = withApp {
        val t = client.register().tokens.accessToken
        val k = key
        val a = client.postJson("/v1/chat/sessions", """{"category":"food"}""", k, t).bodyAsText()
        val b = client.postJson("/v1/chat/sessions", """{"category":"food"}""", k, t).bodyAsText()
        assertEquals(a, b)
        assertEquals(1, Regex("\"session_id\"").findAll(client.getAuthed("/v1/chat/sessions", t).bodyAsText()).count())
    }

    @Test fun categoriesBehindAFlagCantBeOpened() = withApp {
        val t = client.register().tokens.accessToken
        val r = client.postJson("/v1/chat/sessions", """{"category":"shopping"}""", key, t).assertContract("POST", "/v1/chat/sessions")
        assertEquals("forbidden", r.errorCode())
        client.setFlag("home.category.shopping", true)
        client.newChat(t, "shopping")
    }

    @Test fun clarificationThenCartWithTheModel() = withApp {
        val t = client.register().tokens.accessToken
        client.allowAi(t)
        val id = client.newChat(t)
        val first = client.say(t, id, "Milk, brown bread, eggs and bananas").bodyAsText()
        assertTrue("\"next_step\":\"clarify\"" in first && "Which milk should I add?" in first && "\"degraded\":false" in first, first)
        val clr = Regex("\"clarification_id\":\"([^\"]+)\"").find(first)!!.groupValues[1]

        val second = client.say(t, id, "Toned milk 1 L", answer = clr to "o1").bodyAsText()
        assertTrue("\"next_step\":\"compare\"" in second, second)
        assertTrue("{\"line_id\":\"l1\",\"name\":\"Toned milk\",\"quantity\":1,\"size\":\"1 L\"" in second, second)
        assertTrue("\"name\":\"Brown bread\",\"quantity\":1,\"size\":\"400 g\"" in second)
        assertEquals(4, Regex("\"line_id\"").findAll(second).count())

        // A clarification answers once.
        assertEquals("validation_failed", client.postJson("/v1/chat/messages", messageBody(id, "again", clr to "o2"), key, t).assertContract("POST", "/v1/chat/messages").errorCode())

        val detail = client.getAuthed("/v1/chat/sessions/$id", t).assertContract("GET", "/v1/chat/sessions/{sessionId}").bodyAsText()
        assertEquals(4, Regex("\"role\":\"(user|assistant)\"").findAll(detail).count())
        assertTrue("\"title\":\"Milk, brown bread, eggs and bananas\"" in detail)
    }

    @Test fun withoutAiConsentTheDeterministicPathAnswers() = withApp {
        val t = client.register().tokens.accessToken
        val id = client.newChat(t)
        val list = client.say(t, id, "2 x toned milk 1 L\n1 x brown bread").bodyAsText()
        assertTrue("\"degraded\":true" in list && "\"next_step\":\"compare\"" in list, list)
        assertTrue("\"name\":\"Toned milk\",\"quantity\":2,\"size\":\"1 L\"" in list, list)
        val chatty = client.say(t, id, "I need stuff for breakfast").bodyAsText()
        assertTrue("\"degraded\":true" in chatty && "\"next_step\":\"none\"" in chatty && "one per line" in chatty, chatty)
    }

    @Test fun rideChatsBuildARide() = withApp {
        val t = client.register().tokens.accessToken
        client.allowAi(t)
        val id = client.newChat(t, "travel")
        val r = client.postJson("/v1/chat/messages", messageBody(id, "An auto from Home to Airport Terminal 1", installed = "\"uber\",\"ola\""), key, t)
            .assertContract("POST", "/v1/chat/messages").bodyAsText()
        assertTrue("\"ride\":{\"pickup_label\":\"Home\",\"drop_label\":\"Airport Terminal 1\",\"ride_class\":\"auto\",\"when\":null}" in r, r)
    }

    @Test fun usersCantReachOtherUsersChats() = withApp {
        val priya = client.register().tokens.accessToken
        val ravi = client.register(userId = "ravi.s", email = "ravi@example.com", deviceId = "dev-9").tokens.accessToken
        val id = client.newChat(priya)
        client.say(priya, id, "2 x eggs")
        assertEquals(404, client.getAuthed("/v1/chat/sessions/$id", ravi).status.value)
        assertEquals(404, client.delete("/v1/chat/sessions/$id") { bearerAuth(ravi) }.status.value)
        assertEquals("not_found", client.postJson("/v1/chat/messages", messageBody(id, "add milk"), key, ravi).assertContract("POST", "/v1/chat/messages").errorCode())
        assertFalse(id in client.getAuthed("/v1/chat/sessions", ravi).bodyAsText())
    }

    @Test fun contactDetailsAreRedactedBeforeStorageAndTheModel() {
        val seen = mutableListOf<IntentInput>()
        withApp(testServices(intentModel = { input -> seen += input; RuleBasedIntentModel().extract(input) })) {
            val t = client.register().tokens.accessToken
            client.allowAi(t)
            val id = client.newChat(t)
            client.say(t, id, "2 x eggs, call me on +91 98765 43210 or mail priya@example.com, card 4111 1111 1111 1111")
            val stored = client.getAuthed("/v1/chat/sessions/$id", t).bodyAsText()
            for (secret in listOf("98765", "priya@example.com", "4111")) {
                assertFalse(secret in stored, "stored: $secret")
                assertFalse(seen.single().text.contains(secret), "sent to model: $secret")
            }
            assertTrue("[phone]" in seen.single().text && "[email]" in seen.single().text && "[number]" in seen.single().text)
        }
    }

    @Test fun messagesAreRateLimited() = withApp {
        val t = client.register().tokens.accessToken
        val id = client.newChat(t)
        repeat(30) { client.postJson("/v1/chat/messages", messageBody(id, "2 x eggs"), key, t) }
        val r = client.say(t, id, "2 x eggs")
        assertEquals("rate_limited", r.errorCode())
    }

    @Test fun retriedMessagesDontRunTwice() = withApp {
        val t = client.register().tokens.accessToken
        val id = client.newChat(t)
        val k = key
        val a = client.postJson("/v1/chat/messages", messageBody(id, "2 x eggs"), k, t).bodyAsText()
        val b = client.postJson("/v1/chat/messages", messageBody(id, "2 x eggs"), k, t).bodyAsText()
        assertEquals(a, b)
        assertEquals(2, Regex("\"role\"").findAll(client.getAuthed("/v1/chat/sessions/$id", t).bodyAsText()).count())
    }

    @Test fun chatsExpireAfterThirtyDaysOfInactivity() {
        val clock = MutableClock()
        withApp(testServices(clock)) { s ->
            val t = client.register().tokens.accessToken
            val old = client.newChat(t)
            client.say(t, old, "2 x eggs")
            clock.advance(Duration.ofDays(29))
            val recent = s.chat.createSession("priya.k", "grocery").sessionId
            clock.advance(Duration.ofDays(2))
            assertEquals(1, s.chat.purgeExpired())
            assertEquals("not_found", runCatching { s.chat.session("priya.k", old) }.exceptionOrNull()?.message)
            assertEquals(recent, s.chat.session("priya.k", recent).sessionId)
        }
    }

    @Test fun deletingChatHistoryRemovesEveryConversation() = withApp { s ->
        val t = client.register().tokens.accessToken
        repeat(2) { client.say(t, client.newChat(t), "2 x eggs") }
        client.postJson("/v1/me/data/delete", """{"scope":"chat","password":"correct horse battery"}""", key, t)
        s.dataRequests.processPending()
        assertEquals("{\"sessions\":[],\"next_cursor\":null}", client.getAuthed("/v1/chat/sessions", t).bodyAsText())
    }
}
