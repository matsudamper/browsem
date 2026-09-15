package net.matsudamper.browser.download

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ダウンロード完了ファイルのサムネイル/アプリアイコンを読み込む。
 * ダウンロード管理画面のプレビューと、ダウンロード完了通知の largeIcon で共有する
 */
internal sealed interface DownloadThumbnail {
    val bitmap: Bitmap

    data class Thumbnail(override val bitmap: Bitmap) : DownloadThumbnail
    data class AppIcon(override val bitmap: Bitmap) : DownloadThumbnail
}

internal object DownloadThumbnailLoader {
    private const val MIME_TYPE_APK = "application/vnd.android.package-archive"

    /**
     * MediaStore がサムネイルを生成できる画像・動画・音声はサムネイルを、
     * APK は PackageManager で取り出したアプリアイコンを返す。
     * どちらも取得できない場合は null を返す
     */
    suspend fun load(context: Context, fileUri: String, sizePx: Int): DownloadThumbnail? {
        return withContext(Dispatchers.IO) {
            val uri = fileUri.toUri()
            val thumbnail = runCatching {
                context.contentResolver.loadThumbnail(uri, Size(sizePx, sizePx), null)
            }.getOrNull()
            if (thumbnail != null) {
                return@withContext DownloadThumbnail.Thumbnail(thumbnail)
            }
            val mimeType = runCatching { context.contentResolver.getType(uri) }.getOrNull()
            val fileName = getDisplayName(context, uri)
            if (isApk(mimeType, fileName)) {
                loadApkIcon(context, uri, sizePx)?.let {
                    return@withContext DownloadThumbnail.AppIcon(it)
                }
            }
            null
        }
    }

    /**
     * APK ファイルからアプリアイコンを取り出す。解析に失敗した場合は null を返す。
     * PackageManager.getPackageArchiveInfo はファイルパスしか受け付けないため、
     * MediaStore の content:// URI を実ファイルパスに解決してから渡す
     */
    private fun loadApkIcon(context: Context, uri: Uri, sizePx: Int): Bitmap? {
        val packageManager = context.packageManager
        return useFilePath(context, uri) { path ->
            val packageInfo = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(0L))
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageArchiveInfo(path, 0)
                }
            }.getOrNull() ?: return@useFilePath null
            val applicationInfo = packageInfo.applicationInfo ?: return@useFilePath null
            // アイコンのリソースを APK 自身から解決させるため、参照先パスを設定する
            applicationInfo.sourceDir = path
            applicationInfo.publicSourceDir = path
            runCatching {
                applicationInfo.loadIcon(packageManager).toBitmap(width = sizePx, height = sizePx)
            }.getOrNull()
        }
    }

    /** MIME タイプまたは拡張子から APK かどうかを判定する */
    private fun isApk(mimeType: String?, fileName: String?): Boolean {
        if (mimeType.equals(MIME_TYPE_APK, ignoreCase = true)) return true
        return fileName?.endsWith(".apk", ignoreCase = true) == true
    }

    /**
     * MediaStore の content:// URI では lastPathSegment が数値IDになるため、
     * 拡張子を見るには DISPLAY_NAME を取得する必要がある
     */
    private fun getDisplayName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver
                .query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        }.getOrNull()
    }

    /**
     * content:// URI を、ファイルパスを要求する API へ渡せる形に解決して [block] を呼ぶ。
     * MediaStore の DATA 列が使える場合はその実パスを、使えない場合は
     * ファイルディスクリプタ経由の /proc/self/fd パスを渡す。
     * 後者は [block] の実行中のみ有効なため、[block] 内で読み切る必要がある
     */
    private fun <T> useFilePath(context: Context, uri: Uri, block: (path: String) -> T?): T? {
        val resolver = context.contentResolver
        val dataPath = runCatching {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        if (dataPath != null && File(dataPath).canRead()) {
            return runCatching { block(dataPath) }.getOrNull()
        }
        return runCatching {
            resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                block("/proc/self/fd/${descriptor.fd}")
            }
        }.getOrNull()
    }
}
