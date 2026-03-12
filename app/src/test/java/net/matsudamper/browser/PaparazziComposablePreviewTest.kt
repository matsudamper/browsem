package net.matsudamper.browser

import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import sergio.sastre.composable.preview.scanner.android.AndroidComposablePreviewScanner

@Category(PaparazziTestCategory::class)
class PaparazziComposablePreviewTest {

    @get:Rule
    val paparazzi = Paparazzi()

    @Test
    fun snapshot() {
        val filter = System.getProperty("paparazzi.filter", "") ?: ""
        AndroidComposablePreviewScanner()
            .scanPackageTrees("net.matsudamper.browser")
            .includePrivatePreviews()
            .getPreviews()
            .filter { preview ->
                val previewName = preview.previewInfo.name.orEmpty()
                filter.isEmpty() ||
                    preview.toString().contains(filter, ignoreCase = true) ||
                    previewName.contains(filter, ignoreCase = true)
            }
            .forEach { preview ->
                paparazzi.snapshot { preview() }
            }
    }
}
