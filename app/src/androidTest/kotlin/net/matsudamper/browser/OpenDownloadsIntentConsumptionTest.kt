package net.matsudamper.browser

import android.content.Intent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OpenDownloadsIntentConsumptionTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun markOpenDownloadsIntentConsumed_clearsMatchingRequestId() {
        val requestId = "progress:9001"
        val intent = openDownloadsIntent(
            workerId = WORKER_ID,
            requestId = requestId,
        )

        composeRule.activity.setIntent(intent)
        composeRule.activity.markOpenDownloadsIntentConsumed(requestId)

        assertNull(composeRule.activity.intent.action)
        assertNull(composeRule.activity.intent.getStringExtra(DownloadWorker.EXTRA_OPEN_DOWNLOADS_REQUEST_ID))
    }

    @Test
    fun markOpenDownloadsIntentConsumed_skipsMismatchedRequestId() {
        val completeIntent = openDownloadsIntent(
            workerId = WORKER_ID,
            requestId = "complete:12345",
        )

        composeRule.activity.setIntent(completeIntent)
        composeRule.activity.markOpenDownloadsIntentConsumed("progress:9001")

        assertEquals(DownloadWorker.ACTION_OPEN_DOWNLOADS, composeRule.activity.intent.action)
        assertEquals("complete:12345", composeRule.activity.intent.getStringExtra(DownloadWorker.EXTRA_OPEN_DOWNLOADS_REQUEST_ID))
        assertEquals(WORKER_ID, composeRule.activity.intent.getStringExtra(DownloadWorker.EXTRA_WORKER_ID))
    }

    @Test
    fun activityRecreation_clearsConsumedRequestIntent() {
        val requestId = "progress:9001"
        val intent = openDownloadsIntent(
            workerId = WORKER_ID,
            requestId = requestId,
        )

        composeRule.activity.setIntent(intent)
        composeRule.activity.markOpenDownloadsIntentConsumed(requestId)
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        assertNull(composeRule.activity.intent.action)
    }

    private fun openDownloadsIntent(workerId: String, requestId: String): Intent {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return Intent(context, MainActivity::class.java).apply {
            action = DownloadWorker.ACTION_OPEN_DOWNLOADS
            putExtra(DownloadWorker.EXTRA_WORKER_ID, workerId)
            putExtra(DownloadWorker.EXTRA_OPEN_DOWNLOADS_REQUEST_ID, requestId)
        }
    }

    private companion object {
        private const val WORKER_ID = "550e8400-e29b-41d4-a716-446655440000"
    }
}
