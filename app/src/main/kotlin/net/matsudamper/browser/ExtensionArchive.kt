package net.matsudamper.browser

import java.io.File
import java.util.zip.ZipFile

/** WebExtension のマニフェストファイル名。ZIP のルートに存在する。 */
private const val WEB_EXTENSION_MANIFEST_ENTRY = "manifest.json"

/** AMO で署名された XPI に含まれる署名ファイル。 */
private const val WEB_EXTENSION_SIGNATURE_ENTRY = "META-INF/mozilla.rsa"

/**
 * 選択されたファイルが WebExtension のアーカイブかどうかを判定する。
 *
 * XPI は「manifest.json をルートに含む ZIP」であり ZIP との違いは拡張子だけなので、
 * 拡張子ではなくアーカイブの中身で判定する。これにより ZIP / XPI の両方を同じ経路で扱える。
 */
internal fun isWebExtensionArchive(file: File): Boolean {
    return containsEntry(file, WEB_EXTENSION_MANIFEST_ENTRY)
}

/**
 * 署名済みの拡張機能アーカイブかどうかを判定する。
 *
 * AMO で署名された XPI は META-INF/mozilla.rsa を含む。Gecko は実際に証明書を検証するため
 * ここでの判定はあくまで「署名されていないファイルを事前に検出して警告する」ための目安であり、
 * 署名が不正な場合はインストール時に Gecko 側で弾かれる。
 */
internal fun isSignedWebExtensionArchive(file: File): Boolean {
    return containsEntry(file, WEB_EXTENSION_SIGNATURE_ENTRY)
}

/** ZIP に指定エントリが含まれるかを判定する。ZIP として読めない場合は false。 */
private fun containsEntry(file: File, entryName: String): Boolean {
    return runCatching {
        ZipFile(file).use { zip ->
            zip.entries().asSequence().any { it.name.equals(entryName, ignoreCase = true) }
        }
    }.getOrDefault(false)
}
