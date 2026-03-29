package net.matsudamper.browser

import kotlinx.coroutines.suspendCancellableCoroutine
import org.mozilla.geckoview.GeckoResult
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException


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
