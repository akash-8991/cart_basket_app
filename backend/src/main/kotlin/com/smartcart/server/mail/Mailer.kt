package com.smartcart.server.mail

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.sql.DataSource

enum class MailTemplate(val key: String) {
    PASSWORD_RESET_CODE("password_reset_code"),
    USER_ID_REMINDER("user_id_reminder"),
}

/** Sends transactional email. No provider is chosen in Phase 3; see [OutboxMailer]. */
fun interface Mailer {
    fun send(to: String, template: MailTemplate, params: Map<String, String>)
}

/**
 * Development/test sink: writes messages to the email_outbox table, where a developer can read the
 * recovery code with psql. Never used in production — it stores codes in the database.
 */
class OutboxMailer(private val db: DataSource) : Mailer {
    override fun send(to: String, template: MailTemplate, params: Map<String, String>) {
        db.connection.use { c ->
            c.prepareStatement("insert into email_outbox(recipient, template, params) values (?,?,?::jsonb)").use {
                it.setString(1, to); it.setString(2, template.key)
                it.setString(3, JsonObject(params.mapValues { e -> JsonPrimitive(e.value) }).toString())
                it.executeUpdate()
            }
        }
    }
}
