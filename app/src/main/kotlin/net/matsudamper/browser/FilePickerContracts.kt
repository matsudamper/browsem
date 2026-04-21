package net.matsudamper.browser

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

/**
 * GeckoViewのファイルプロンプトで使用するファイルピッカー用のActivityResultContract。
 * ACTION_OPEN_DOCUMENTではなくACTION_GET_CONTENTを使用することで、
 * GeckoViewがURIにアクセスできるようにする。
 * 複数のMIMEタイプフィルタに対応している。
 *
 * @param input 許可するMIMEタイプの配列（例: `["image/*", "application/pdf"]`）。
 *   空配列や単一要素の場合はそのまま使用し、複数の場合は`"*/*"`を設定してEXTRA_MIME_TYPESでフィルタする。
 */
internal class GetFileContent : ActivityResultContract<Array<String>, Uri?>() {
    override fun createIntent(context: Context, input: Array<String>): Intent {
        return Intent(Intent.ACTION_GET_CONTENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .apply {
                if (input.size == 1) {
                    type = input[0]
                } else {
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, input)
                }
            }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
        if (resultCode != Activity.RESULT_OK) return null
        return intent?.data
    }
}

/**
 * GeckoViewのファイルプロンプトで使用する複数ファイルピッカー用のActivityResultContract。
 * ACTION_GET_CONTENTを使用し、複数ファイル選択とMIMEタイプフィルタに対応している。
 *
 * @param input 許可するMIMEタイプの配列（例: `["image/*", "video/*"]`）。
 *   空配列や単一要素の場合はそのまま使用し、複数の場合は`"*/*"`を設定してEXTRA_MIME_TYPESでフィルタする。
 */
internal class GetMultipleFileContent : ActivityResultContract<Array<String>, List<Uri>>() {
    override fun createIntent(context: Context, input: Array<String>): Intent {
        return Intent(Intent.ACTION_GET_CONTENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            .apply {
                if (input.size == 1) {
                    type = input[0]
                } else {
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, input)
                }
            }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
        if (resultCode != Activity.RESULT_OK || intent == null) {
            return emptyList()
        }
        val clipData = intent.clipData
        return if (clipData != null) {
            (0 until clipData.itemCount).mapNotNull { clipData.getItemAt(it).uri }
        } else {
            listOfNotNull(intent.data)
        }
    }
}
