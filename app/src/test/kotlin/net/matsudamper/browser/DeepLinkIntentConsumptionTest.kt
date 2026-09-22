package net.matsudamper.browser

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 外部から受け取った VIEW Intent の重複処理判定を検証する。 */
@RunWith(RobolectricTestRunner::class)
class DeepLinkIntentConsumptionTest {

    @Test
    fun `受け取ったばかりの Intent は未処理`() {
        assertFalse(DeepLinkIntentConsumption.isConsumed(viewIntent(URL)))
    }

    @Test
    fun `印を付けた Intent は再処理しない`() {
        val intent = viewIntent(URL)

        DeepLinkIntentConsumption.markConsumed(intent)

        assertTrue(DeepLinkIntentConsumption.isConsumed(intent))
    }

    @Test
    fun `同じ URL でも別の Intent なら処理する`() {
        val first = viewIntent(URL)
        DeepLinkIntentConsumption.markConsumed(first)

        val second = viewIntent(URL)

        assertFalse(DeepLinkIntentConsumption.isConsumed(second))
    }

    @Test
    fun `印は Intent の複製にも引き継がれる`() {
        val intent = viewIntent(URL)
        DeepLinkIntentConsumption.markConsumed(intent)

        // Activity 再生成時に getIntent() から渡される Intent は Parcel 経由で複製される
        assertTrue(DeepLinkIntentConsumption.isConsumed(Intent(intent)))
    }

    private fun viewIntent(url: String): Intent {
        return Intent(Intent.ACTION_VIEW, Uri.parse(url))
    }

    private companion object {
        const val URL = "https://example.com/"
    }
}
