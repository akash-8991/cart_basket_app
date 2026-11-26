package com.smartcart.server.audit

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.sql.DataSource

/** Context of the request an audit event belongs to. IPs are never stored raw. */
data class RequestContext(val correlationId: String, val ipHash: String?, val deviceId: String? = null)

/** Keyed hash of client IPs: lets us spot one IP across events without keeping the address. */
class IpHasher(key: ByteArray) {
    private val spec = SecretKeySpec(key, "HmacSHA256")
    fun hash(ip: String?): String? = ip?.let {
        val mac = Mac.getInstance("HmacSHA256").apply { init(spec) }
        mac.doFinal(it.toByteArray()).joinToString("") { b -> "%02x".format(b) }.take(32)
    }
}

/**
 * Append-only audit trail (table audit_log, protected by a trigger). [detail] holds IDs and codes only —
 * never passwords, tokens, emails or free text from users.
 */
class Audit(private val db: DataSource) {
    private val log = LoggerFactory.getLogger(Audit::class.java)

    fun record(action: String, outcome: String, ctx: RequestContext, actor: String? = null, target: String? = null, detail: Map<String, String> = emptyMap()) {
        runCatching {
            db.connection.use { c ->
                c.prepareStatement(
                    "insert into audit_log(actor_user_id, action, target, outcome, correlation_id, ip_hash, detail) values (?,?,?,?,?,?,?::jsonb)",
                ).use { st ->
                    st.setString(1, actor); st.setString(2, action); st.setString(3, target); st.setString(4, outcome)
                    st.setString(5, ctx.correlationId); st.setString(6, ctx.ipHash)
                    st.setString(7, JsonObject(detail.mapValues { JsonPrimitive(it.value) }).toString())
                    st.executeUpdate()
                }
            }
        }.onFailure { log.error("audit write failed for action={}", action, it) }
    }
}
