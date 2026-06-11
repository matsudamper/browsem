package net.matsudamper.browser

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.ScreenOrientation
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
                // @Preview の widthDp/heightDp 指定をデバイス設定に反映する
                val baseConfig = DeviceConfig.NEXUS_5
                val pixelsPerDp = baseConfig.density.dpiValue / 160
                val widthDp = preview.previewInfo.widthDp
                val heightDp = preview.previewInfo.heightDp
                val screenWidth = if (widthDp > 0) widthDp * pixelsPerDp else baseConfig.screenWidth
                val screenHeight = if (heightDp > 0) heightDp * pixelsPerDp else baseConfig.screenHeight
                paparazzi.unsafeUpdateConfig(
                    deviceConfig = baseConfig.copy(
                        screenWidth = screenWidth,
                        screenHeight = screenHeight,
                        orientation = if (screenWidth > screenHeight) {
                            ScreenOrientation.LANDSCAPE
                        } else {
                            ScreenOrientation.PORTRAIT
                        },
                    ),
                )
                paparazzi.snapshot { preview() }
            }
    }
}
