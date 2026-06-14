package net.matsudamper.browser

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Navigation 3 の既知バグ（removeObserver が非メインスレッドから呼ばれる）で
 * テストが失敗した場合に自動リトライする TestRule。
 */
class RetryOnKnownFlakyRule(private val maxRetries: Int = 1) : TestRule {
    override fun apply(base: Statement, description: Description): Statement {
        return object : Statement() {
            override fun evaluate() {
                var lastError: Throwable? = null
                for (attempt in 0..maxRetries) {
                    try {
                        base.evaluate()
                        return
                    } catch (e: Throwable) {
                        lastError = e
                        if (attempt < maxRetries && isKnownFlakyException(e)) {
                            println(
                                "RetryOnKnownFlakyRule: Navigation 3 の既知スレッドバグを検出。" +
                                    "リトライ (${attempt + 2}/${maxRetries + 1}): ${e.message}",
                            )
                        } else {
                            throw e
                        }
                    }
                }
                throw lastError!!
            }
        }
    }

    private fun isKnownFlakyException(e: Throwable): Boolean {
        val message = e.message ?: return false
        return e is IllegalStateException &&
            message.contains("removeObserver must be called on the main thread")
    }
}
