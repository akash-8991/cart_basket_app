package com.smartcart.server.auth

import com.smartcart.server.db.transaction
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

data class User(val userId: String, val email: String, val emailVerified: Boolean, val passwordHash: String, val deletionRequested: Boolean = false)

data class Device(val deviceId: String, val platform: String, val appVersion: String, val osVersion: String?, val model: String? = null) {
    /** What the sessions list shows: the model when the app sends one (CR-002 B), else the OS. */
    val label: String get() = model ?: osVersion?.let { "Android $it" } ?: "Android device"
}

data class Session(
    val sessionId: String,
    val userId: String,
    val device: Device,
    val remembered: Boolean,
    val lastSeenAt: Instant,
    val expiresAt: Instant,
    val revokedAt: Instant?,
) {
    fun isActive(now: Instant) = revokedAt == null && expiresAt.isAfter(now)
}

sealed interface RefreshUse {
    data object Unknown : RefreshUse
    /** The token was already used: someone holds a copy. */
    data class Reused(val session: Session) : RefreshUse
    data class Fresh(val session: Session) : RefreshUse
}

data class Challenge(val challengeId: String, val userId: String, val codeHash: ByteArray, val expiresAt: Instant, val attempts: Int, val consumedAt: Instant?)

/**
 * All auth persistence. Every query that touches a session or challenge on behalf of a user filters by
 * that user's ID, so one user can never act on another's rows (threat model A-15).
 */
class AuthRepository(private val db: DataSource) {

    /** False if the user ID is taken. */
    fun createUser(userId: String, email: String, passwordHash: String, termsVersion: String, privacyVersion: String): Boolean =
        db.connection.use { c ->
            c.prepareStatement(
                "insert into users(user_id, email, password_hash, terms_version, privacy_version) values (?,?,?,?,?) on conflict (user_id) do nothing",
            ).use { st ->
                st.setString(1, userId); st.setString(2, email); st.setString(3, passwordHash); st.setString(4, termsVersion); st.setString(5, privacyVersion)
                st.executeUpdate() == 1
            }
        }

    fun findUser(userId: String): User? = db.connection.use { c ->
        c.prepareStatement("select user_id, email, email_verified, password_hash, deletion_requested_at is not null from users where user_id = ?").use { st ->
            st.setString(1, userId)
            st.executeQuery().use { rs -> if (rs.next()) User(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getString(4), rs.getBoolean(5)) else null }
        }
    }

    fun userIdsForEmail(email: String): List<String> = db.connection.use { c ->
        c.prepareStatement("select user_id from users where lower(email) = lower(?) and deletion_requested_at is null order by user_id").use { st ->
            st.setString(1, email)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
    }

    fun createSession(sessionId: String, userId: String, device: Device, remembered: Boolean, now: Instant, expiresAt: Instant, refreshHash: ByteArray) =
        db.transaction { c ->
            c.prepareStatement(
                "insert into sessions(session_id, user_id, device_id, platform, app_version, os_version, remembered, created_at, last_seen_at, expires_at, device_model) values (?,?,?,?,?,?,?,?,?,?,?)",
            ).use { st ->
                st.setString(1, sessionId); st.setString(2, userId); st.setString(3, device.deviceId); st.setString(4, device.platform)
                st.setString(5, device.appVersion); st.setString(6, device.osVersion); st.setBoolean(7, remembered)
                st.setTimestamp(8, Timestamp.from(now)); st.setTimestamp(9, Timestamp.from(now)); st.setTimestamp(10, Timestamp.from(expiresAt)); st.setString(11, device.model)
                st.executeUpdate()
            }
            insertRefresh(c, refreshHash, sessionId, now)
        }

    fun session(sessionId: String): Session? = db.connection.use { c -> loadSession(c, sessionId) }

    /**
     * Marks a refresh token used, atomically: of two concurrent uses of the same token exactly one is
     * [RefreshUse.Fresh] and the other is [RefreshUse.Reused].
     */
    fun useRefreshToken(hash: ByteArray, now: Instant): RefreshUse = db.transaction { c ->
        val sessionId = c.prepareStatement("update refresh_tokens set used_at = ? where token_hash = ? and used_at is null returning session_id").use { st ->
            st.setTimestamp(1, Timestamp.from(now)); st.setBytes(2, hash)
            st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
        if (sessionId != null) return@transaction RefreshUse.Fresh(loadSession(c, sessionId)!!)
        val reusedSession = c.prepareStatement("select session_id from refresh_tokens where token_hash = ?").use { st ->
            st.setBytes(1, hash)
            st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
        if (reusedSession == null) RefreshUse.Unknown else RefreshUse.Reused(loadSession(c, reusedSession)!!)
    }

    fun rotate(sessionId: String, newRefreshHash: ByteArray, now: Instant) = db.transaction { c ->
        insertRefresh(c, newRefreshHash, sessionId, now)
        c.prepareStatement("update sessions set last_seen_at = ? where session_id = ?").use { st ->
            st.setTimestamp(1, Timestamp.from(now)); st.setString(2, sessionId); st.executeUpdate()
        }
    }

    fun touch(sessionId: String, now: Instant) = db.connection.use { c ->
        c.prepareStatement("update sessions set last_seen_at = ? where session_id = ? and last_seen_at < ?").use { st ->
            st.setTimestamp(1, Timestamp.from(now)); st.setString(2, sessionId); st.setTimestamp(3, Timestamp.from(now.minusSeconds(60)))
            st.executeUpdate()
        }
    }

    fun activeSessions(userId: String, now: Instant): List<Session> = db.connection.use { c ->
        c.prepareStatement("$SESSION_SELECT where user_id = ? and revoked_at is null and expires_at > ? order by last_seen_at desc").use { st ->
            st.setString(1, userId); st.setTimestamp(2, Timestamp.from(now))
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.toSession()) } }
        }
    }

    /** Revokes [sessionId] only if it belongs to [userId] and is still active. */
    fun revokeSession(userId: String, sessionId: String, reason: String, now: Instant): Boolean = db.connection.use { c ->
        c.prepareStatement("update sessions set revoked_at = ?, revoke_reason = ? where session_id = ? and user_id = ? and revoked_at is null and expires_at > ?").use { st ->
            st.setTimestamp(1, Timestamp.from(now)); st.setString(2, reason); st.setString(3, sessionId); st.setString(4, userId); st.setTimestamp(5, Timestamp.from(now))
            st.executeUpdate() == 1
        }
    }

    /** Revokes every active session of [userId] except [keep]; returns how many. */
    fun revokeAll(userId: String, keep: String?, reason: String, now: Instant): Int = db.connection.use { c ->
        c.prepareStatement("update sessions set revoked_at = ?, revoke_reason = ? where user_id = ? and revoked_at is null and expires_at > ? and session_id is distinct from ?").use { st ->
            st.setTimestamp(1, Timestamp.from(now)); st.setString(2, reason); st.setString(3, userId); st.setTimestamp(4, Timestamp.from(now)); st.setString(5, keep)
            st.executeUpdate()
        }
    }

    /** Replaces any earlier code: only the newest one works (CR-003 §2). */
    fun putEmailVerification(userId: String, codeHash: ByteArray, now: Instant, expiresAt: Instant) = db.connection.use { c ->
        c.prepareStatement(
            """insert into email_verifications(user_id, code_hash, created_at, expires_at, attempts) values (?,?,?,?,0)
               on conflict (user_id) do update set code_hash = excluded.code_hash, created_at = excluded.created_at, expires_at = excluded.expires_at, attempts = 0""",
        ).use { st ->
            st.setString(1, userId); st.setBytes(2, codeHash); st.setTimestamp(3, Timestamp.from(now)); st.setTimestamp(4, Timestamp.from(expiresAt)); st.executeUpdate()
        }
    }

    data class EmailVerification(val codeHash: ByteArray, val expiresAt: Instant, val attempts: Int)

    fun emailVerification(userId: String): EmailVerification? = db.connection.use { c ->
        c.prepareStatement("select code_hash, expires_at, attempts from email_verifications where user_id = ?").use { st ->
            st.setString(1, userId)
            st.executeQuery().use { rs -> if (rs.next()) EmailVerification(rs.getBytes(1), rs.getTimestamp(2).toInstant(), rs.getInt(3)) else null }
        }
    }

    fun recordWrongVerificationCode(userId: String) = db.connection.use { c ->
        c.prepareStatement("update email_verifications set attempts = attempts + 1 where user_id = ?").use { st -> st.setString(1, userId); st.executeUpdate() }
    }

    /** Marks the email verified and spends the code, in one transaction. */
    fun completeEmailVerification(userId: String) = db.transaction { c ->
        c.prepareStatement("update users set email_verified = true where user_id = ?").use { st -> st.setString(1, userId); st.executeUpdate() }
        c.prepareStatement("delete from email_verifications where user_id = ?").use { st -> st.setString(1, userId); st.executeUpdate() }
    }

    fun createChallenge(challengeId: String, userId: String, codeHash: ByteArray, now: Instant, expiresAt: Instant) = db.connection.use { c ->
        c.prepareStatement("insert into recovery_challenges(challenge_id, user_id, code_hash, created_at, expires_at) values (?,?,?,?,?)").use { st ->
            st.setString(1, challengeId); st.setString(2, userId); st.setBytes(3, codeHash); st.setTimestamp(4, Timestamp.from(now)); st.setTimestamp(5, Timestamp.from(expiresAt))
            st.executeUpdate()
        }
    }

    fun challenge(challengeId: String): Challenge? = db.connection.use { c ->
        c.prepareStatement("select challenge_id, user_id, code_hash, expires_at, attempts, consumed_at from recovery_challenges where challenge_id = ?").use { st ->
            st.setString(1, challengeId)
            st.executeQuery().use { rs ->
                if (!rs.next()) null else Challenge(rs.getString(1), rs.getString(2), rs.getBytes(3), rs.getTimestamp(4).toInstant(), rs.getInt(5), rs.getTimestamp(6)?.toInstant())
            }
        }
    }

    fun recordWrongCode(challengeId: String) = db.connection.use { c ->
        c.prepareStatement("update recovery_challenges set attempts = attempts + 1 where challenge_id = ?").use { st -> st.setString(1, challengeId); st.executeUpdate() }
    }

    /** Sets the new password and consumes the challenge in one transaction; false if it was already used. */
    fun resetPassword(challengeId: String, userId: String, passwordHash: String, now: Instant): Boolean = db.transaction { c ->
        val consumed = c.prepareStatement("update recovery_challenges set consumed_at = ? where challenge_id = ? and user_id = ? and consumed_at is null").use { st ->
            st.setTimestamp(1, Timestamp.from(now)); st.setString(2, challengeId); st.setString(3, userId); st.executeUpdate() == 1
        }
        if (consumed) {
            c.prepareStatement("update users set password_hash = ?, password_changed_at = ? where user_id = ?").use { st ->
                st.setString(1, passwordHash); st.setTimestamp(2, Timestamp.from(now)); st.setString(3, userId); st.executeUpdate()
            }
        }
        consumed
    }

    private fun insertRefresh(c: Connection, hash: ByteArray, sessionId: String, now: Instant) {
        c.prepareStatement("insert into refresh_tokens(token_hash, session_id, created_at) values (?,?,?)").use { st ->
            st.setBytes(1, hash); st.setString(2, sessionId); st.setTimestamp(3, Timestamp.from(now)); st.executeUpdate()
        }
    }

    private fun loadSession(c: Connection, sessionId: String): Session? =
        c.prepareStatement("$SESSION_SELECT where session_id = ?").use { st ->
            st.setString(1, sessionId)
            st.executeQuery().use { rs -> if (rs.next()) rs.toSession() else null }
        }

    private fun ResultSet.toSession() = Session(
        sessionId = getString("session_id"),
        userId = getString("user_id"),
        device = Device(getString("device_id"), getString("platform"), getString("app_version"), getString("os_version"), getString("device_model")),
        remembered = getBoolean("remembered"),
        lastSeenAt = getTimestamp("last_seen_at").toInstant(),
        expiresAt = getTimestamp("expires_at").toInstant(),
        revokedAt = getTimestamp("revoked_at")?.toInstant(),
    )

    private companion object {
        const val SESSION_SELECT = "select session_id, user_id, device_id, platform, app_version, os_version, device_model, remembered, last_seen_at, expires_at, revoked_at from sessions"
    }
}
