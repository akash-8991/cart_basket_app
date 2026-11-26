package com.smartcart.server

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.core.ConsoleAppender
import net.logstash.logback.argument.StructuredArguments.kv
import ch.qos.logback.classic.joran.JoranConfigurator
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Uses the real logback.xml encoder: secrets and personal data never reach the log stream. */
class LogRedactionTest {
    /** The production logback.xml, loaded into its own context so the test-only config doesn't interfere. */
    private val prod: LoggerContext = LoggerContext().also { ctx ->
        ctx.setMDCAdapter(ch.qos.logback.classic.util.LogbackMDCAdapter())
        JoranConfigurator().apply { context = ctx }.doConfigure(javaClass.getResource("/logback.xml")!!)
    }

    private fun encode(message: String, vararg args: Any): String {
        val root = prod.getLogger(Logger.ROOT_LOGGER_NAME)
        @Suppress("UNCHECKED_CAST")
        val appender = root.getAppender("JSON") as ConsoleAppender<ch.qos.logback.classic.spi.ILoggingEvent>
        val event = LoggingEvent("test", root, Level.INFO, message, null, args)
        return String(appender.encoder.encode(event))
    }

    @Test fun maskedByFieldName() {
        val out = encode("login attempt", kv("password", "hunter2-correct-horse"), kv("refresh_token", "rt_abc123"), kv("user_id", "priya.k"))
        assertFalse("hunter2" in out, out)
        assertFalse("rt_abc123" in out, out)
        assertTrue("priya.k" in out, "non-sensitive fields stay readable: $out")
    }

    @Test fun maskedByValuePattern() {
        val out = encode("upstream said Authorization: Bearer eyJhbGciOiJFUzI1NiJ9.abc.def for priya@example.com key sk-ant-api03-SECRET")
        assertFalse("eyJhbGciOiJFUzI1NiJ9" in out, out)
        assertFalse("priya@example.com" in out, out)
        assertFalse("sk-ant-api03-SECRET" in out, out)
    }
}
