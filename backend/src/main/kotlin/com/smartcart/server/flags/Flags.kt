package com.smartcart.server.flags

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.sql.DataSource

data class Flag(val key: String, val enabled: Boolean, val updatedAt: Instant, val updatedBy: String, val reason: String)

/**
 * Server-controlled feature flags and kill switches (contracts/feature-flags.yaml). Values live in the
 * database so an operator change reaches every instance without a redeploy; each instance re-reads them
 * at most every [cacheTtl].
 */
class Flags(private val db: DataSource, private val clock: Clock, private val cacheTtl: Duration = Duration.ofSeconds(5)) {
    @Volatile private var cache: Map<String, Flag> = emptyMap()
    @Volatile private var loadedAt: Instant = Instant.EPOCH

    /** Inserts every catalogue key that isn't in the table yet; never overwrites an operator's value. */
    fun seed(catalogue: Map<String, Boolean>) {
        db.connection.use { c ->
            c.prepareStatement("insert into feature_flags(flag_key, enabled) values (?, ?) on conflict (flag_key) do nothing").use { st ->
                catalogue.forEach { (k, v) -> st.setString(1, k); st.setBoolean(2, v); st.addBatch() }
                st.executeBatch()
            }
        }
        invalidate()
    }

    fun all(): Map<String, Flag> {
        val now = clock.instant()
        if (Duration.between(loadedAt, now) >= cacheTtl || cache.isEmpty()) {
            cache = db.connection.use { c ->
                c.createStatement().executeQuery("select flag_key, enabled, updated_at, updated_by, reason from feature_flags").use { rs ->
                    buildMap { while (rs.next()) put(rs.getString(1), Flag(rs.getString(1), rs.getBoolean(2), rs.getTimestamp(3).toInstant(), rs.getString(4), rs.getString(5))) }
                }
            }
            loadedAt = now
        }
        return cache
    }

    /** Unknown keys are off: a missing flag never switches something on. */
    fun isOn(key: String): Boolean = all()[key]?.enabled ?: false

    fun exists(key: String): Boolean = key in all()

    fun set(key: String, enabled: Boolean, actor: String, reason: String): Flag {
        val now = clock.instant()
        db.connection.use { c ->
            c.prepareStatement("update feature_flags set enabled = ?, updated_at = ?, updated_by = ?, reason = ? where flag_key = ?").use { st ->
                st.setBoolean(1, enabled); st.setTimestamp(2, Timestamp.from(now)); st.setString(3, actor); st.setString(4, reason); st.setString(5, key)
                require(st.executeUpdate() == 1) { "unknown flag $key" }
            }
        }
        invalidate()
        return Flag(key, enabled, now, actor, reason)
    }

    fun invalidate() { loadedAt = Instant.EPOCH }

    companion object {
        /**
         * Default values from feature-flags.yaml (packaged as a resource), plus a `provider.<key>.kill`
         * switch, default off, for every provider that has an `enabled` flag.
         */
        fun catalogue(): Map<String, Boolean> {
            val yaml = Flags::class.java.getResourceAsStream("/contracts/feature-flags.yaml") ?: error("feature-flags.yaml not packaged")
            val flags = ObjectMapper(YAMLFactory()).readTree(yaml).path("flags")
            val defaults = flags.properties().associate { (k, v) -> k to v.path("default").asBoolean(false) }
            val kills = defaults.keys.filter { it.startsWith("provider.") && it.endsWith(".enabled") }
                .associate { it.removeSuffix(".enabled") + ".kill" to false }
            return defaults + kills
        }

        fun isHidden(key: String): Boolean {
            val yaml = Flags::class.java.getResourceAsStream("/contracts/feature-flags.yaml") ?: return false
            return ObjectMapper(YAMLFactory()).readTree(yaml).path("flags").path(key).path("hidden").asBoolean(false)
        }
    }
}
