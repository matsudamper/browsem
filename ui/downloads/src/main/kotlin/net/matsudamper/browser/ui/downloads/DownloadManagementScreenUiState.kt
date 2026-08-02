package net.matsudamper.browser.ui.downloads

import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.ImageBitmap
import java.util.UUID

@Stable
data class DownloadManagementScreenUiState(
    val isLoading: Boolean,
    val downloads: List<DownloadItem>,
    val callbacks: Callbacks,
) {
    @Stable
    sealed interface DownloadStatus {
        data class InProgress(
            val progress: Int,
            val totalRead: Long,
            val contentLength: Long,
            val isIndeterminate: Boolean,
        ) : DownloadStatus

        /**
         * ダウンロード完了。
         */
        data class Completed(
            val fileUri: String,
        ) : DownloadStatus

        /**
         * ダウンロード失敗。
         * [canResume] が true の場合は再開ボタンを表示する。
         */
        data class Failed(val canResume: Boolean) : DownloadStatus

        data object Cancelled : DownloadStatus

        /**
         * ダウンロード一時停止中。再開ボタンを表示する。
         */
        data class Paused(
            val progress: Int,
            val totalRead: Long,
            val contentLength: Long,
        ) : DownloadStatus
    }

    /**
     * ダウンロード完了アイテムの左側に表示するプレビュー。
     * 種類によって表示方法（クロップ / 全体表示 / アイコン描画）が異なる
     */
    @Stable
    sealed interface Preview {
        /** 画像・動画などのサムネイル。表示領域いっぱいにクロップして表示する */
        data class Thumbnail(val image: ImageBitmap) : Preview

        /** APK から取り出したアプリアイコン。全体が収まるように表示する */
        data class AppIcon(val image: ImageBitmap) : Preview

        /** サムネイルを取得できないファイルに MIME タイプから割り当てる汎用アイコン */
        data class FileType(val fileType: DownloadFileType) : Preview
    }

    /** MIME タイプから判定したファイル種別。汎用アイコンの出し分けに使う */
    enum class DownloadFileType {
        /** zip・gzip・tar などの圧縮アーカイブ */
        ARCHIVE,
        PDF,
        VIDEO,
        AUDIO,

        /** 上記のいずれにも当てはまらないファイル */
        UNKNOWN,
    }

    @Stable
    data class DownloadItem(
        val id: UUID,
        val fileName: String,
        val status: DownloadStatus,
        val enqueuedAt: Long,
        /** ダウンロード開始時に表示していたページのURL。不明な場合は null */
        val originPageUrl: String?,
    )

    @Stable
    data class Callbacks(
        val onCancel: (UUID) -> Unit,
        /** ダウンロード中のアイテムを一時停止する */
        val onPause: (UUID) -> Unit,
        val onOpenFile: (fileUri: String) -> Unit,
        val onOpenDownloadsFolder: () -> Unit,
        /** 失敗したダウンロードを再開する */
        val onResume: (UUID) -> Unit,
        /** ダウンロード開始時のページを新しいタブで開く */
        val onOpenOriginPage: (url: String) -> Unit,
        /** ファイル URI からプレビューを読み込む。サムネイルを取得できない場合は汎用アイコンを返す */
        val loadPreview: suspend (fileUri: String) -> Preview,
    )
}
