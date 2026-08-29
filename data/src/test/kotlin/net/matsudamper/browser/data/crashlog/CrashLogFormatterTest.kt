package net.matsudamper.browser.data.crashlog

import org.junit.Assert.assertTrue
import org.junit.Test

class CrashLogFormatterTest {
    @Test
    fun extractTitle_usesFirstLineOfStackTrace() {
        val throwable = RuntimeException("test message")

        val title = CrashLogFormatter.extractTitle(throwable)

        assertTrue(title.contains("RuntimeException"))
        assertTrue(title.contains("test message"))
    }

    @Test
    fun formatBody_includesThreadNameAndStackTrace() {
        val throwable = IllegalStateException("broken state")

        val body = CrashLogFormatter.formatBody(Thread.currentThread(), throwable)

        assertTrue(body.startsWith("Thread: "))
        assertTrue(body.contains("IllegalStateException"))
        assertTrue(body.contains("broken state"))
    }
}
