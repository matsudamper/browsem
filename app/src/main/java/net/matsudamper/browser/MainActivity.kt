package net.matsudamper.browser

import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import java.util.concurrent.CancellationException

class MainActivity : ComponentActivity() {
    private lateinit var runtime: GeckoRuntime
    private var pendingActivityResult: GeckoResult<Intent>? = null

    private val geckoActivityLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val pendingResult = pendingActivityResult ?: return@registerForActivityResult
        pendingActivityResult = null

        if (result.resultCode == Activity.RESULT_OK) {
            pendingResult.complete(result.data ?: Intent())
        } else {
            pendingResult.completeExceptionally(
                CancellationException("Gecko activity cancelled. resultCode=${result.resultCode}")
            )
        }
    }

    private val activityDelegate = GeckoRuntime.ActivityDelegate { pendingIntent ->
        if (pendingActivityResult != null) {
            return@ActivityDelegate GeckoResult.fromException(
                IllegalStateException("Another Gecko activity request is already pending.")
            )
        }

        val result = GeckoResult<Intent>()
        pendingActivityResult = result

        try {
            geckoActivityLauncher.launch(IntentSenderRequest.Builder(pendingIntent).build())
        } catch (e: IntentSender.SendIntentException) {
            pendingActivityResult = null
            result.completeExceptionally(e)
        }

        result
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runtime = GeckoRuntime.getDefault(this)
        runtime.setActivityDelegate(activityDelegate)
        setContent {
            GeckoBrowserTab(runtime = runtime)
        }
    }

    override fun onDestroy() {
        pendingActivityResult?.completeExceptionally(
            CancellationException("Activity was destroyed before Gecko activity completed.")
        )
        pendingActivityResult = null
        if (::runtime.isInitialized && runtime.getActivityDelegate() === activityDelegate) {
            runtime.setActivityDelegate(null)
        }
        super.onDestroy()
    }
}
