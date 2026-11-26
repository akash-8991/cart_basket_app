package com.smartcart.server.chat

import java.security.SecureRandom
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.Base64
import javax.sql.DataSource

data class SessionRow(val sessionId: String, val userId: String, val category: String, val title: String, val createdAt: Instant, val updatedAt: Instant?, val state: String)

data class MessageRow(val messageId: String, val role: String, val text: String, val createdAt: Instant, val turn: String?)

/** Chat persistence. Every read and write is scoped to the owning user (threat model A-15). */
class ChatRepository(private val db: DataSource) {
    private val random = SecureRandom()
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    fun newId(prefix: String) = prefix + "_" + b64.encodeToString(ByteArray(12).also(random::nextBytes))

    fun createSession(userId: String, category: String, title: String, now: Instant): SessionRow = db.connection.use { c ->
        val id = newId("chs")
        c.prepareStatement("insert into chat_sessions(session_id, user_id, category, title, created_at) values (?,?,?,?,?)").use { st ->
            st.setString(1, id); st.setString(2, userId); st.setString(3, category); st.setString(4, title); st.setTimestamp(5, Timestamp.from(now))
            st.executeUpdate()
        }
        SessionRow(id, userId, category, title, now, null, "{}")
    }

    fun session(userId: String, sessionId: String): SessionRow? = db.connection.use { c ->
        c.prepareStatement("$SELECT where session_id = ? and user_id = ?").use { st ->
            st.setString(1, sessionId); st.setString(2, userId)
            st.executeQuery().use { rs -> if (rs.next()) rs.toSession() else null }
        }
    }

    fun sessions(userId: String, limit: Int, after: Pair<Instant, String>?): List<SessionRow> = db.connection.use { c ->
        val sql = "$SELECT where user_id = ?" + (if (after != null) " and (created_at, session_id) < (?, ?)" else "") + " order by created_at desc, session_id desc limit ?"
        c.prepareStatement(sql).use { st ->
            var i = 1
            st.setString(i++, userId)
            if (after != null) { st.setTimestamp(i++, Timestamp.from(after.first)); st.setString(i++, after.second) }
            st.setInt(i, limit)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toSession()) } }
        }
    }

    fun deleteSession(userId: String, sessionId: String): Boolean = db.connection.use { c ->
        c.prepareStatement("delete from chat_sessions where session_id = ? and user_id = ?").use { st ->
            st.setString(1, sessionId); st.setString(2, userId); st.executeUpdate() == 1
        }
    }

    fun deleteAll(userId: String): Int = db.connection.use { c ->
        c.prepareStatement("delete from chat_sessions where user_id = ?").use { st -> st.setString(1, userId); st.executeUpdate() }
    }

    /** Retention: removes conversations untouched since [cutoff]. */
    fun purgeInactiveSince(cutoff: Instant): Int = db.connection.use { c ->
        c.prepareStatement("delete from chat_sessions where coalesce(updated_at, created_at) < ?").use { st -> st.setTimestamp(1, Timestamp.from(cutoff)); st.executeUpdate() }
    }

    fun addMessage(sessionId: String, role: String, text: String, now: Instant, turnJson: String? = null, messageId: String = newId("msg")): String = db.connection.use { c ->
        c.prepareStatement("insert into chat_messages(message_id, session_id, role, text, created_at, turn) values (?,?,?,?,?,?::jsonb)").use { st ->
            st.setString(1, messageId); st.setString(2, sessionId); st.setString(3, role); st.setString(4, text); st.setTimestamp(5, Timestamp.from(now)); st.setString(6, turnJson)
            st.executeUpdate()
        }
        messageId
    }

    fun messages(sessionId: String): List<MessageRow> = db.connection.use { c ->
        c.prepareStatement("select message_id, role, text, created_at, turn::text from chat_messages where session_id = ? order by seq").use { st ->
            st.setString(1, sessionId)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(MessageRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant(), rs.getString(5))) } }
        }
    }

    /** The stored assistant turn for [messageId], only if it is in one of [userId]'s sessions. */
    fun turn(userId: String, messageId: String): String? = db.connection.use { c ->
        c.prepareStatement("select m.turn::text from chat_messages m join chat_sessions s using (session_id) where m.message_id = ? and s.user_id = ?").use { st ->
            st.setString(1, messageId); st.setString(2, userId)
            st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    fun afterTurn(sessionId: String, state: String, title: String?, now: Instant) = db.connection.use { c ->
        c.prepareStatement("update chat_sessions set state = ?::jsonb, updated_at = ?, title = coalesce(?, title) where session_id = ?").use { st ->
            st.setString(1, state); st.setTimestamp(2, Timestamp.from(now)); st.setString(3, title); st.setString(4, sessionId)
            st.executeUpdate()
        }
    }

    private fun ResultSet.toSession() = SessionRow(
        getString("session_id"), getString("user_id"), getString("category"), getString("title"),
        getTimestamp("created_at").toInstant(), getTimestamp("updated_at")?.toInstant(), getString("state"),
    )

    private companion object {
        const val SELECT = "select session_id, user_id, category, title, created_at, updated_at, state::text as state from chat_sessions"
    }
}
