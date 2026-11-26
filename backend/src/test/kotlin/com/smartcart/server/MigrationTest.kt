package com.smartcart.server

import com.smartcart.server.support.TestDb
import org.postgresql.util.PSQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MigrationTest {
    @Test fun migrationsApplyToAnEmptyDatabase() {
        val ds = TestDb.fresh()
        ds.connection.use { c ->
            val rs = c.createStatement().executeQuery("select count(*) from flyway_schema_history where success")
            rs.next(); assertTrue(rs.getInt(1) >= 1)
        }
    }

    @Test fun auditLogIsAppendOnly() {
        val ds = TestDb.fresh()
        ds.connection.use { c ->
            c.createStatement().execute("insert into audit_log(action, outcome) values ('auth.login', 'success')")
            assertFailsWith<PSQLException> { c.createStatement().execute("update audit_log set outcome = 'failure'") }
            assertFailsWith<PSQLException> { c.createStatement().execute("delete from audit_log") }
            val rs = c.createStatement().executeQuery("select outcome from audit_log")
            rs.next(); assertEquals("success", rs.getString(1))
        }
    }
}
