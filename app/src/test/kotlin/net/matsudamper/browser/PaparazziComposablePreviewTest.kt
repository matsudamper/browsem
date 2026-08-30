package net.matsudamper.browser

import android.content.res.Configuration
import java.util.Locale
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import com.android.resources.ScreenOrientation
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category
import sergio.sastre.composable.preview.scanner.android.AndroidComposablePreviewScanner
import sergio.sastre.composable.preview.scanner.android.AndroidPreviewInfo
import sergio.sastre.composable.preview.scanner.core.preview.ComposablePreview

@Category(PaparazziTestCategory::class)
class PaparazziComposablePreviewTest {

    @get:Rule
    val paparazzi = Paparazzi()

    @Test
    fun snapshot() {
        val filter = System.getProperty("paparazzi.filter", "") ?: ""
        val allTargets = AndroidComposablePreviewScanner()
            .scanPackageTrees(PACKAGE_TREE)
            .includePrivatePreviews()
            .getPreviews()
            .map { preview -> preview to preview.snapshotName() }

        // Paparazzi は名前を小文字化してファイル名にするため、小文字化して重複を判定する。
        // 重複したままだと同じファイルに上書きされ、片方のスナップショットが検証されなくなる。
        // filter で絞る前の全 Preview を対象にしないと、絞り込みの仕方によって衝突を見逃す
        val duplicatedNames = allTargets
            .groupingBy { (_, snapshotName) -> snapshotName.lowercase(Locale.US) }
            .eachCount()
            .filterValues { count -> count > 1 }
            .keys
        check(duplicatedNames.isEmpty()) {
            "スナップショット名が重複しています: ${duplicatedNames.joinToString()}"
        }

        allTargets
            .filter { (_, snapshotName) ->
                filter.isEmpty() || snapshotName.contains(filter, ignoreCase = true)
            }
            .forEach { (preview, snapshotName) ->
                paparazzi.unsafeUpdateConfig(deviceConfig = preview.deviceConfig())
                paparazzi.snapshot(name = snapshotName) { preview() }
            }
    }

    /**
     * Preview ごとに一意なスナップショット名を組み立てる。
     *
     * 名前を渡さない場合 Paparazzi は `<パッケージ>_<クラス>_<メソッド>.png` へ出力するため、
     * 全 Preview が同じファイルに上書きされてしまう。
     * `@Preview(name = ...)` は省略可能かつ同名を付けられるので、宣言クラス名・メソッド名も併せて一意にする。
     */
    private fun ComposablePreview<AndroidPreviewInfo>.snapshotName(): String {
        return listOfNotNull(
            declaringClass.substringAfterLast('.'),
            methodName,
            previewInfo.name.takeIf { it.isNotEmpty() },
            // @PreviewParameter 指定時は 1 メソッドから複数の Preview が生成されるため添字で区別する
            previewIndex?.toString(),
        ).joinToString(separator = "_") { it.sanitizeForFileName() }
    }

    /** [@Preview(name = ...)] には任意の文字を書けるため、ファイル名に使えない文字を落とす */
    private fun String.sanitizeForFileName(): String {
        return replace(INVALID_FILE_NAME_CHARS, "_")
    }

    /** [@Preview] の指定を Paparazzi のデバイス設定に反映する */
    private fun ComposablePreview<AndroidPreviewInfo>.deviceConfig(): DeviceConfig {
        val baseConfig = DeviceConfig.NEXUS_5
        val pixelsPerDp = baseConfig.density.dpiValue / DEFAULT_DENSITY_DPI
        val widthDp = previewInfo.widthDp
        val heightDp = previewInfo.heightDp
        val screenWidth = if (widthDp > 0) widthDp * pixelsPerDp else baseConfig.screenWidth
        val screenHeight = if (heightDp > 0) heightDp * pixelsPerDp else baseConfig.screenHeight
        return baseConfig.copy(
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            orientation = if (screenWidth > screenHeight) {
                ScreenOrientation.LANDSCAPE
            } else {
                ScreenOrientation.PORTRAIT
            },
            // uiMode を反映しないと Light / Dark が同じ設定で描画され、ダークテーマを検証できない
            nightMode = if (previewInfo.uiMode.isNightMode()) NightMode.NIGHT else NightMode.NOTNIGHT,
        )
    }

    private fun Int.isNightMode(): Boolean {
        return this and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    private companion object {
        const val PACKAGE_TREE = "net.matsudamper.browser"
        const val DEFAULT_DENSITY_DPI = 160
        val INVALID_FILE_NAME_CHARS = """[<>:"/\\|?*\s]""".toRegex()
    }
}
