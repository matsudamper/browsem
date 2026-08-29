package net.matsudamper.browser.data.crashlog

import java.io.PrintWriter
import java.io.StringWriter

object CrashLogFormatter {
    fun extractTitle(throwable: Throwable): String {
        return throwable.stackTraceToString()
            .lineSequence()
            .firstOrNull()
            ?.trim()
            .orEmpty()
            .ifEmpty { throwable::class.java.name }
    }

    fun formatBody(thread: Thread, throwable: Throwable): String {
        val writer = StringWriter()
        val printWriter = PrintWriter(writer)
        printWriter.println("Thread: ${thread.name}")
        throwable.printStackTrace(printWriter)
        printWriter.flush()
        return writer.toString()
    }
}
