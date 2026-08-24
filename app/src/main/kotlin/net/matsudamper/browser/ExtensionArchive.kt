package net.matsudamper.browser

import java.io.File
import java.util.zip.ZipFile

/** WebExtension のマニフェストファイル名。ZIP のルートに存在する。 */
private const val WEB_EXTENSION_MANIFEST_ENTRY = "manifest.json"

/**
 * 選択されたファイルが WebExtension のアーカイブかどうかを判定する。
 *
 * XPI は「manifest.json をルートに含む ZIP」であり ZIP との違いは拡張子だけなので、
 * 拡張子ではなくアーカイブの中身で判定する。これにより ZIP / XPI の両方を同じ経路で扱える。
 */
internal fun isWebExtensionArchive(file: File): Boolean {
    return runCatching {
        ZipFile(file).use { zip ->
            zip.getEntry(WEB_EXTENSION_MANIFEST_ENTRY) != null
        }
    }.getOrDefault(false)
}
