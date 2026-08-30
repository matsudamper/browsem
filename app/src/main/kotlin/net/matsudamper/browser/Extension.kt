package net.matsudamper.browser

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import org.mozilla.geckoview.GeckoResult

suspend fun <T> GeckoResult<T>.awaitGecko(): T? = suspendCancellableCoroutine { cont ->
    accept(
        { value ->
            if (cont.isActive) {
                cont.resume(value)
            }
        },
        { throwable ->
            if (cont.isActive) {
                cont.resumeWithException(throwable ?: RuntimeException("Unknown Gecko error"))
            }
        },
    )
}
