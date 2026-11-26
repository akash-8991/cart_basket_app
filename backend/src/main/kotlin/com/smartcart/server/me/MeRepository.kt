package com.smartcart.server.me

import com.smartcart.server.db.transaction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.SecureRandom
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.Base64
import javax.sql.DataSource

data class ProfileRow(val userId: String, val email: String, val emailVerified: Boolean, val privacyMode: String, val createdAt: Instant)

data class ConsentRow(val key: String, val granted: Boolean, val version: String, val updatedAt: Instant)

data class ConsentEventRow(val key: String, val granted: Boolean, val version: String, val at: Instant)

data class ActivityRow(
    val eventId: String, val at: Instant, val type: String, val summary: String, val provider: String?, val taskId: String?,
    val summaryKey: String? = null, val summaryArgs: Map<String, String>? = null,
)

data class DataRequestRow(val requestId: String, val userId: String, val kind: String, val scope: String, val status: String, val createdAt: Instant)

/** Everything under /v1/me. Every query is keyed by the caller's user ID; there is no cross-user path. */
class MeRepository(private val db: DataSource) {
    private val random = SecureRandom()
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    fun newId(prefix: String) = prefix + "_" + b64.encodeToString(ByteArray(12).also(random::nextBytes))

    fun profile(userId: String): ProfileRow? = db.connection.use { c ->
        c.prepareStatement("select user_id, email, email_verified, privacy_mode, created_at from users where user_id = ?").use { st ->
            st.setString(1, userId)
            st.executeQuery().use { rs -> if (rs.next()) ProfileRow(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getString(4), rs.getTimestamp(5).toInstant()) else null }
        }
    }

    fun consents(userId: String): List<ConsentRow> = db.connection.use { c ->
        c.prepareStatement("select consent_key, granted, version, updated_at from consents where user_id = ? order by consent_key").use { st ->
            st.setString(1, userId)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(ConsentRow(rs.getString(1), rs.getBoolean(2), rs.getString(3), rs.getTimestamp(4).toInstant())) } }
        }
    }

    fun consentHistory(userId: String): List<ConsentEventRow> = db.connection.use { c ->
        c.prepareStatement("select consent_key, granted, version, at from consent_events where user_id = ? order by at, id").use { st ->
            st.setString(1, userId)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(ConsentEventRow(rs.getString(1), rs.getBoolean(2), rs.getString(3), rs.getTimestamp(4).toInstant())) } }
        }
    }

    /**
     * Sets a consent and appends to its history in one transaction. Returns the previous grant state
     * (null if never set), so callers only log real changes.
     */
    fun setConsent(userId: String, key: String, granted: Boolean, version: String, now: Instant, correlationId: String): Boolean? = db.transaction { c ->
        val previous = c.prepareStatement("select granted from consents where user_id = ? and consent_key = ? for update").use { st ->
            st.setString(1, userId); st.setString(2, key)
            st.executeQuery().use { rs -> if (rs.next()) rs.getBoolean(1) else null }
        }
        c.prepareStatement(
            """insert into consents(user_id, consent_key, granted, version, updated_at) values (?,?,?,?,?)
               on conflict (user_id, consent_key) do update set granted = excluded.granted, version = excluded.version, updated_at = excluded.updated_at""",
        ).use { st ->
            st.setString(1, userId); st.setString(2, key); st.setBoolean(3, granted); st.setString(4, version); st.setTimestamp(5, Timestamp.from(now))
            st.executeUpdate()
        }
        c.prepareStatement("insert into consent_events(user_id, consent_key, granted, version, at, correlation_id) values (?,?,?,?,?,?)").use { st ->
            st.setString(1, userId); st.setString(2, key); st.setBoolean(3, granted); st.setString(4, version); st.setTimestamp(5, Timestamp.from(now)); st.setString(6, correlationId)
            st.executeUpdate()
        }
        previous
    }

    fun addActivity(
        userId: String, type: String, summary: String, at: Instant, provider: String? = null, taskId: String? = null,
        summaryKey: String? = null, summaryArgs: Map<String, String>? = null,
    ): String = db.connection.use { c ->
        val id = newId("evt")
        c.prepareStatement("insert into activity_events(event_id, user_id, at, type, summary, provider, task_id, summary_key, summary_args) values (?,?,?,?,?,?,?,?,?::jsonb)").use { st ->
            st.setString(1, id); st.setString(2, userId); st.setTimestamp(3, Timestamp.from(at)); st.setString(4, type)
            st.setString(5, summary); st.setString(6, provider); st.setString(7, taskId); st.setString(8, summaryKey)
            st.setString(9, summaryArgs?.let { a -> JsonObject(a.mapValues { JsonPrimitive(it.value) }).toString() })
            st.executeUpdate()
        }
        id
    }

    /** Newest first; [after] is the (at, event_id) of the last row on the previous page. */
    fun activity(userId: String, limit: Int, after: Pair<Instant, String>?): List<ActivityRow> = db.connection.use { c ->
        val sql = "select event_id, at, type, summary, provider, task_id, summary_key, summary_args::text from activity_events where user_id = ?" +
            (if (after != null) " and (at, event_id) < (?, ?)" else "") + " order by at desc, event_id desc limit ?"
        c.prepareStatement(sql).use { st ->
            var i = 1
            st.setString(i++, userId)
            if (after != null) { st.setTimestamp(i++, Timestamp.from(after.first)); st.setString(i++, after.second) }
            st.setInt(i, limit)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toActivity()) } }
        }
    }

    fun createDataRequest(userId: String, kind: String, scope: String, now: Instant): DataRequestRow = db.connection.use { c ->
        val id = newId(if (kind == "export") "exp" else "del")
        c.prepareStatement("insert into data_requests(request_id, user_id, kind, scope, created_at) values (?,?,?,?,?)").use { st ->
            st.setString(1, id); st.setString(2, userId); st.setString(3, kind); st.setString(4, scope); st.setTimestamp(5, Timestamp.from(now))
            st.executeUpdate()
        }
        DataRequestRow(id, userId, kind, scope, "queued", now)
    }

    fun markDeletionRequested(userId: String, now: Instant) = db.connection.use { c ->
        c.prepareStatement("update users set deletion_requested_at = coalesce(deletion_requested_at, ?) where user_id = ?").use { st ->
            st.setTimestamp(1, Timestamp.from(now)); st.setString(2, userId); st.executeUpdate()
        }
    }

    /** Claims up to [max] queued requests (skipping rows another worker holds) and marks them in progress. */
    fun claimPending(max: Int): List<DataRequestRow> = db.transaction { c ->
        c.prepareStatement(
            """update data_requests set status = 'in_progress' where request_id in (
                 select request_id from data_requests where status = 'queued' order by created_at limit ? for update skip locked)
               returning request_id, user_id, kind, scope, status, created_at""",
        ).use { st ->
            st.setInt(1, max)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(DataRequestRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getTimestamp(6).toInstant())) } }
        }
    }

    fun finishRequest(requestId: String, status: String, now: Instant) = db.connection.use { c ->
        c.prepareStatement("update data_requests set status = ?, completed_at = ? where request_id = ?").use { st ->
            st.setString(1, status); st.setTimestamp(2, Timestamp.from(now)); st.setString(3, requestId); st.executeUpdate()
        }
    }

    /** Erases the account; sessions, tokens, consents, activity and requests go with it (ON DELETE CASCADE). */
    fun eraseAccount(userId: String) = db.connection.use { c ->
        c.prepareStatement("delete from users where user_id = ?").use { st -> st.setString(1, userId); st.executeUpdate() }
    }

    private fun ResultSet.toActivity() = ActivityRow(
        getString(1), getTimestamp(2).toInstant(), getString(3), getString(4), getString(5), getString(6), getString(7),
        getString(8)?.let { Json.parseToJsonElement(it).jsonObject.mapValues { (_, v) -> v.jsonPrimitive.content } },
    )

    fun setPrivacyMode(userId: String, mode: String) = db.connection.use { c ->
        c.prepareStatement("update users set privacy_mode = ? where user_id = ?").use { st -> st.setString(1, mode); st.setString(2, userId); st.executeUpdate() }
    }
}

/** Records user-facing activity; other features (auth, tasks, history) call this. */
class ActivityLog(private val repo: MeRepository) {
    /** [key] and [args] (CR-002 E) let the app show its own copy; [args] must never hold personal data. */
    fun record(
        userId: String, type: ActivityType, summary: String, at: Instant, provider: String? = null, taskId: String? = null,
        key: String? = null, args: Map<String, String>? = null,
    ) {
        repo.addActivity(userId, type.wire, summary, at, provider, taskId, key ?: type.wire, args)
    }
}

enum class ActivityType(val wire: String) {
    AUTOMATION_APPROVED("automation_approved"), AUTOMATION_STOPPED("automation_stopped"), AUTOMATION_COMPLETED("automation_completed"),
    AUTOMATION_FAILED("automation_failed"), CONSENT_GRANTED("consent_granted"), CONSENT_REVOKED("consent_revoked"),
    HISTORY_IMPORTED("history_imported"), DATA_EXPORTED("data_exported"), DATA_DELETED("data_deleted"), SESSION_REVOKED("session_revoked"),
}
