package net.matsudamper.browser

import app.cash.paparazzi.Paparazzi
import org.junit.Rule
import org.junit.Test
import sergio.sastre.composable.preview.scanner.android.AndroidComposablePreviewScanner

class PaparazziComposablePreviewTest {

    @get:Rule
    val paparazzi = Paparazzi()

    @Test
    fun snapshot() {
        AndroidComposablePreviewScanner()
            .scanPackageTrees("net.matsudamper.browser")
            .getPreviews()
            .forEach { preview ->
                paparazzi.snapshot { preview() }
            }
    }
}
