package net.matsudamper.browser

import android.content.Context
import android.util.Log
import java.io.File

private const val TAG = "BundledJapaneseFontInstaller"

/** Gecko のフォント設定から参照する、同梱日本語フォントのファミリー名。 */
internal const val BUNDLED_JAPANESE_FONT_FAMILY = "Noto Sans JP"

private const val BUNDLED_FONT_ASSET_PATH = "fonts/NotoSansJP-Regular.ttf"
private const val INSTALLED_FONT_FILE_PREFIX = "browsem-"
private const val INSTALLED_FONT_FILE_NAME = "${INSTALLED_FONT_FILE_PREFIX}noto-sans-jp-regular-v1.ttf"

/**
 * Gecko はフォントをファミリー名で探すため、端末の CJK フォント構成（TTC の日本語フェイスや
 * OEM 独自フォント）によっては日本語の字形を持つフォントを見つけられず、漢字が中国語字形になる。
 * 端末に依存しないよう同梱の日本語フォントを Gecko プロファイルの fonts ディレクトリに配置し、
 * フォント設定からファミリー名で参照できるようにする。
 *
 * Gecko は起動時に fonts ディレクトリを走査するため、GeckoRuntime 生成前に呼ぶ。
 * プロファイルが未作成の初回起動では配置できず、次回起動から反映される。
 */
internal class BundledJapaneseFontInstaller(
    private val context: Context,
) {
    fun install() {
        runCatching {
            val profileDir = findGeckoProfileDir() ?: return
            val fontsDir = File(profileDir, "fonts")
            val installedFile = File(fontsDir, INSTALLED_FONT_FILE_NAME)
            if (!installedFile.exists()) {
                copyBundledFont(installedFile)
            }
            fontsDir.listFiles()
                ?.filter { it.name.startsWith(INSTALLED_FONT_FILE_PREFIX) && it != installedFile }
                ?.forEach { it.delete() }
        }.onFailure { error ->
            Log.w(TAG, "同梱日本語フォントの配置に失敗", error)
        }
    }

    private fun copyBundledFont(installedFile: File) {
        installedFile.parentFile?.mkdirs()
        val tempFile = File(installedFile.parentFile, "${installedFile.name}.tmp")
        context.assets.open(BUNDLED_FONT_ASSET_PATH).use { input ->
            tempFile.outputStream().use { output -> input.copyTo(output) }
        }
        check(tempFile.renameTo(installedFile)) { "同梱日本語フォントの配置に失敗" }
    }

    /** GeckoView のプロファイルは HOME（filesDir）/mozilla 配下に profiles.ini で管理される。 */
    private fun findGeckoProfileDir(): File? {
        val mozillaDir = File(context.filesDir, "mozilla")
        return readProfileDirFromProfilesIni(mozillaDir) ?: findDefaultProfileDir(mozillaDir)
    }

    private fun readProfileDirFromProfilesIni(mozillaDir: File): File? {
        val profilesIni = File(mozillaDir, "profiles.ini")
        if (!profilesIni.exists()) return null
        val profilePath = profilesIni.readLines()
            .firstOrNull { it.startsWith("Path=") }
            ?.removePrefix("Path=")
            ?: return null
        val profileDir = if (profilePath.startsWith("/")) File(profilePath) else File(mozillaDir, profilePath)
        return profileDir.takeIf { it.isDirectory }
    }

    /** profiles.ini が書き出される前でも、Gecko が作る <salt>.default ディレクトリがあれば使う。 */
    private fun findDefaultProfileDir(mozillaDir: File): File? {
        return mozillaDir.listFiles()
            ?.filter { it.isDirectory && it.name.endsWith(".default") }
            ?.maxByOrNull { it.lastModified() }
    }
}
