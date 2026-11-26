package com.smartcart.server.db

import com.smartcart.server.config.DbConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import java.sql.Connection
import javax.sql.DataSource

object Database {
    fun pool(cfg: DbConfig): HikariDataSource = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = cfg.url
            username = cfg.user
            password = cfg.password
            maximumPoolSize = cfg.maxPoolSize
            isAutoCommit = true
            poolName = "smartcart"
        },
    )

    /** Applies db/migration/V*.sql. Runs at startup; the service never starts on an out-of-date schema. */
    fun migrate(ds: DataSource) {
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate()
    }

    fun isReady(ds: DataSource): Boolean = runCatching {
        ds.connection.use { c -> c.createStatement().use { it.execute("select 1") } }
    }.getOrDefault(false)
}

/** Runs [block] in one transaction; rolls back on any exception. */
fun <T> DataSource.transaction(block: (Connection) -> T): T = connection.use { c ->
    c.autoCommit = false
    try {
        block(c).also { c.commit() }
    } catch (t: Throwable) {
        c.rollback()
        throw t
    } finally {
        c.autoCommit = true
    }
}
