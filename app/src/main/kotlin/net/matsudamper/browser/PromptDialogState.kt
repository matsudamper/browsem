package net.matsudamper.browser

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.matsudamper.browser.feature.addressautofill.AddressAutofillSuggestionItem
import net.matsudamper.browser.feature.forminputautofill.FormInputAutofillHost
import net.matsudamper.browser.feature.forminputautofill.FormInputSaveDialogRequest
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.Autocomplete
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession

/**
 * JavaScript のプロンプトダイアログ状態を管理する。
 * BrowserTabScreenState から分離し、PromptDelegate と UI ダイアログの橋渡しを行う。
 */
@Stable
internal class PromptDialogState(
    override val coroutineScope: CoroutineScope,
) : FormInputAutofillHost {

    // --- Alert (window.alert()) ---
    var pendingAlertPrompt by mutableStateOf<GeckoSession.PromptDelegate.AlertPrompt?>(null)
    var pendingAlertResult by mutableStateOf<GeckoResult<GeckoSession.PromptDelegate.PromptResponse>?>(null)

    // --- Button (window.confirm()) ---
    var pendingButtonPrompt by mutableStateOf<GeckoSession.PromptDelegate.ButtonPrompt?>(null)
    var pendingButtonResult by mutableStateOf<GeckoResult<GeckoSession.PromptDelegate.PromptResponse>?>(null)

    // --- Text (window.prompt()) ---
    var pendingTextPrompt by mutableStateOf<GeckoSession.PromptDelegate.TextPrompt?>(null)
    var pendingTextResult by mutableStateOf<GeckoResult<GeckoSession.PromptDelegate.PromptResponse>?>(null)

    // --- Choice (<select>) ---
    var pendingChoicePrompt by mutableStateOf<GeckoSession.PromptDelegate.ChoicePrompt?>(null)
    var pendingChoiceResult by mutableStateOf<GeckoResult<GeckoSession.PromptDelegate.PromptResponse>?>(null)

    // --- Color (<input type="color">) ---
    var pendingColorPrompt by mutableStateOf<GeckoSession.PromptDelegate.ColorPrompt?>(null)
    var pendingColorResult by mutableStateOf<GeckoResult<GeckoSession.PromptDelegate.PromptResponse>?>(null)

    // --- DateTime (<input type="date/time/...">) ---
    var pendingDateTimePrompt by mutableStateOf<GeckoSession.PromptDelegate.DateTimePrompt?>(null)
    var pendingDateTimeResult by mutableStateOf<GeckoResult<GeckoSession.PromptDelegate.PromptResponse>?>(null)

    // --- Auth (HTTP 認証) ---
    var pendingAuthPrompt by mutableStateOf<GeckoSession.PromptDelegate.AuthPrompt?>(null)
    var pendingAuthResult by mutableStateOf<GeckoResult<GeckoSession.PromptDelegate.PromptResponse>?>(null)

    // --- File (<input type="file">) ---
    var pendingFilePrompt by mutableStateOf<GeckoSession.PromptDelegate.FilePrompt?>(null)
    var pendingFileResult by mutableStateOf<GeckoResult<GeckoSession.PromptDelegate.PromptResponse>?>(null)

    // --- BeforeUnload (window.onbeforeunload) ---
    var pendingBeforeUnloadPrompt by mutableStateOf<GeckoSession.PromptDelegate.BeforeUnloadPrompt?>(null)
    var pendingBeforeUnloadResult by mutableStateOf<GeckoResult<GeckoSession.PromptDelegate.PromptResponse>?>(null)

    // --- RepostConfirm (フォーム再送信確認) ---
    var pendingRepostConfirmPrompt by mutableStateOf<GeckoSession.PromptDelegate.RepostConfirmPrompt?>(null)
    var pendingRepostConfirmResult by mutableStateOf<GeckoResult<GeckoSession.PromptDelegate.PromptResponse>?>(null)

    // --- Web Share (navigator.share) ---
    var pendingWebSharePrompt by mutableStateOf<GeckoSession.PromptDelegate.SharePrompt?>(null)
    var pendingWebShareResult by mutableStateOf<GeckoResult<GeckoSession.PromptDelegate.PromptResponse>?>(null)

    /** Web Share の共有シート起動要求。Activity の OneShot イベントは Channel 経由で UI へ渡す */
    val webShareLaunchChannel = Channel<Unit>(Channel.CONFLATED)

    var pendingAddressSaveAddress by mutableStateOf<Autocomplete.Address?>(null)
    var pendingAddressSavePrompt by mutableStateOf<GeckoSession.PromptDelegate.AutocompleteRequest<Autocomplete.AddressSaveOption>?>(null)
    var pendingAddressSaveResult by mutableStateOf<GeckoResult<GeckoSession.PromptDelegate.PromptResponse>?>(null)
    override var focusedAutofillKind: String? = null
    override var onAddressSelectOptions: ((List<Autocomplete.AddressSelectOption>) -> Unit)? = null
    override var autofillBarHideGeneration: Int = 0
    var addressAutofillBar by mutableStateOf<AddressAutofillBarUiState?>(null)
    var pendingFormInputSaveDialog by mutableStateOf<FormInputSaveDialogRequest?>(null)

    override fun showAddressAutofillBar(items: List<AddressAutofillSuggestionItem>) {
        if (items.isEmpty()) {
            addressAutofillBar = null
            return
        }
        addressAutofillBar = AddressAutofillBarUiState(
            items = items.map { item ->
                AddressAutofillBarUiState.Item(
                    label = item.label,
                    kind = item.kind,
                    onClick = item.onClick,
                )
            },
        )
    }

    override fun hideAddressAutofillBar() {
        addressAutofillBar = null
    }

    override fun showFormInputSaveDialog(request: FormInputSaveDialogRequest) {
        pendingFormInputSaveDialog = request
    }

    override fun dismissFormInputSaveDialog() {
        pendingFormInputSaveDialog?.onDismiss?.invoke()
        pendingFormInputSaveDialog = null
    }

    fun confirmFormInputSave() {
        val request = pendingFormInputSaveDialog ?: return
        request.onConfirm()
        pendingFormInputSaveDialog = null
    }

    // ================================================================
    // Actions
    // ================================================================

    fun dismissAlertPrompt() {
        val prompt = pendingAlertPrompt ?: return
        pendingAlertResult?.complete(prompt.dismiss())
        pendingAlertPrompt = null
        pendingAlertResult = null
    }

    fun confirmButtonPrompt(positive: Boolean) {
        val prompt = pendingButtonPrompt ?: return
        val type = if (positive) {
            GeckoSession.PromptDelegate.ButtonPrompt.Type.POSITIVE
        } else {
            GeckoSession.PromptDelegate.ButtonPrompt.Type.NEGATIVE
        }
        pendingButtonResult?.complete(prompt.confirm(type))
        pendingButtonPrompt = null
        pendingButtonResult = null
    }

    fun dismissButtonPrompt() {
        val prompt = pendingButtonPrompt ?: return
        pendingButtonResult?.complete(prompt.dismiss())
        pendingButtonPrompt = null
        pendingButtonResult = null
    }

    fun confirmTextPrompt(value: String) {
        val prompt = pendingTextPrompt ?: return
        pendingTextResult?.complete(prompt.confirm(value))
        pendingTextPrompt = null
        pendingTextResult = null
    }

    fun dismissTextPrompt() {
        val prompt = pendingTextPrompt ?: return
        pendingTextResult?.complete(prompt.dismiss())
        pendingTextPrompt = null
        pendingTextResult = null
    }

    fun confirmChoicePromptSingle(choice: GeckoSession.PromptDelegate.ChoicePrompt.Choice) {
        val prompt = pendingChoicePrompt ?: return
        pendingChoiceResult?.complete(prompt.confirm(choice))
        pendingChoicePrompt = null
        pendingChoiceResult = null
    }

    fun confirmChoicePromptMultiple(choices: Array<GeckoSession.PromptDelegate.ChoicePrompt.Choice>) {
        val prompt = pendingChoicePrompt ?: return
        pendingChoiceResult?.complete(prompt.confirm(choices))
        pendingChoicePrompt = null
        pendingChoiceResult = null
    }

    fun dismissChoicePrompt() {
        val prompt = pendingChoicePrompt ?: return
        pendingChoiceResult?.complete(prompt.dismiss())
        pendingChoicePrompt = null
        pendingChoiceResult = null
    }

    fun confirmColorPrompt(color: String) {
        val prompt = pendingColorPrompt ?: return
        pendingColorResult?.complete(prompt.confirm(color))
        pendingColorPrompt = null
        pendingColorResult = null
    }

    fun dismissColorPrompt() {
        val prompt = pendingColorPrompt ?: return
        pendingColorResult?.complete(prompt.dismiss())
        pendingColorPrompt = null
        pendingColorResult = null
    }

    fun confirmDateTimePrompt(datetime: String) {
        val prompt = pendingDateTimePrompt ?: return
        pendingDateTimeResult?.complete(prompt.confirm(datetime))
        pendingDateTimePrompt = null
        pendingDateTimeResult = null
    }

    fun dismissDateTimePrompt() {
        val prompt = pendingDateTimePrompt ?: return
        pendingDateTimeResult?.complete(prompt.dismiss())
        pendingDateTimePrompt = null
        pendingDateTimeResult = null
    }

    fun confirmAuthPrompt(username: String, password: String) {
        val prompt = pendingAuthPrompt ?: return
        pendingAuthResult?.complete(prompt.confirm(username, password))
        pendingAuthPrompt = null
        pendingAuthResult = null
    }

    fun confirmAuthPromptPasswordOnly(password: String) {
        val prompt = pendingAuthPrompt ?: return
        pendingAuthResult?.complete(prompt.confirm(password))
        pendingAuthPrompt = null
        pendingAuthResult = null
    }

    fun dismissAuthPrompt() {
        val prompt = pendingAuthPrompt ?: return
        pendingAuthResult?.complete(prompt.dismiss())
        pendingAuthPrompt = null
        pendingAuthResult = null
    }

    fun confirmFilePrompt(context: Context, uris: Array<Uri>) {
        val prompt = pendingFilePrompt ?: return
        val result = pendingFileResult
        pendingFilePrompt = null
        pendingFileResult = null
        coroutineScope.launch {
            try {
                // ACTION_GET_CONTENT が返す content URI は一時的な読み取り権限しか持たない場合があり、
                // GeckoView が非同期で読み取る際に権限が失効する可能性がある。
                // そのため、コンテンツをキャッシュファイルにコピーしてから GeckoView に渡す。
                val cachedUris = withContext(Dispatchers.IO) {
                    uris.map { uri -> copyToCache(context, uri) ?: uri }.toTypedArray()
                }
                result?.complete(prompt.confirm(context, cachedUris))
            } catch (e: CancellationException) {
                // 画面破棄などでスコープがキャンセルされた場合、GeckoResult を dismiss で完了させてハングを防ぐ
                result?.complete(prompt.dismiss())
                throw e
            }
        }
    }

    fun dismissFilePrompt() {
        val prompt = pendingFilePrompt ?: return
        pendingFileResult?.complete(prompt.dismiss())
        pendingFilePrompt = null
        pendingFileResult = null
    }

    private fun copyToCache(context: Context, uri: Uri): Uri? {
        return try {
            val cacheDir = context.filePromptsCacheDir
            if (!cacheDir.exists() && !cacheDir.mkdirs()) {
                Log.w("PromptDialogState", "キャッシュディレクトリの作成に失敗: $cacheDir")
                return null
            }
            val mimeType = resolveMimeType(context, uri)
            val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
            val fileName = if (extension != null) {
                "${UUID.randomUUID()}.$extension"
            } else {
                UUID.randomUUID().toString()
            }
            val destFile = File(cacheDir, fileName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return null
            Uri.fromFile(destFile)
        } catch (e: Exception) {
            Log.w("PromptDialogState", "コンテンツ URI のキャッシュコピーに失敗", e)
            null
        }
    }

    /**
     * content URI の MIME タイプを解決する。
     * ContentResolver.getType(uri) を第一候補とし、
     * null の場合のみ DISPLAY_NAME の拡張子から MimeTypeMap で補完する。
     * どちらも不明な場合は "application/octet-stream" を返す。
     */
    private fun resolveMimeType(context: Context, uri: Uri): String {
        // ContentResolver.getType() が最も信頼性が高い
        val mimeFromResolver = context.contentResolver.getType(uri)
        if (mimeFromResolver != null) return mimeFromResolver

        // null の場合のみ DISPLAY_NAME の拡張子から補完
        val displayName = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                } else {
                    null
                }
            }
        }.getOrNull()

        if (displayName != null) {
            val dotIndex = displayName.lastIndexOf('.')
            if (dotIndex > 0 && dotIndex < displayName.length - 1) {
                val ext = displayName.substring(dotIndex + 1)
                val mimeFromExt = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                if (mimeFromExt != null) return mimeFromExt
            }
        }

        // それでも不明なら未知のバイナリとして扱う
        return "application/octet-stream"
    }

    fun confirmBeforeUnloadPrompt(allow: Boolean) {
        val prompt = pendingBeforeUnloadPrompt ?: return
        pendingBeforeUnloadResult?.complete(
            prompt.confirm(if (allow) AllowOrDeny.ALLOW else AllowOrDeny.DENY),
        )
        pendingBeforeUnloadPrompt = null
        pendingBeforeUnloadResult = null
    }

    fun dismissBeforeUnloadPrompt() {
        val prompt = pendingBeforeUnloadPrompt ?: return
        pendingBeforeUnloadResult?.complete(prompt.dismiss())
        pendingBeforeUnloadPrompt = null
        pendingBeforeUnloadResult = null
    }

    fun confirmRepostConfirmPrompt(allow: Boolean) {
        val prompt = pendingRepostConfirmPrompt ?: return
        pendingRepostConfirmResult?.complete(
            prompt.confirm(if (allow) AllowOrDeny.ALLOW else AllowOrDeny.DENY),
        )
        pendingRepostConfirmPrompt = null
        pendingRepostConfirmResult = null
    }

    fun dismissRepostConfirmPrompt() {
        val prompt = pendingRepostConfirmPrompt ?: return
        pendingRepostConfirmResult?.complete(prompt.dismiss())
        pendingRepostConfirmPrompt = null
        pendingRepostConfirmResult = null
    }

    fun confirmWebSharePrompt() {
        val prompt = pendingWebSharePrompt ?: return
        val result = pendingWebShareResult
        clearWebSharePending()
        if (result == null || prompt.isComplete) return
        result.complete(
            prompt.confirm(GeckoSession.PromptDelegate.SharePrompt.Result.SUCCESS),
        )
    }

    fun dismissWebSharePrompt() {
        val prompt = pendingWebSharePrompt ?: return
        val result = pendingWebShareResult
        clearWebSharePending()
        if (result == null || prompt.isComplete) return
        result.complete(prompt.dismiss())
    }

    fun failWebSharePrompt() {
        val prompt = pendingWebSharePrompt ?: return
        val result = pendingWebShareResult
        clearWebSharePending()
        if (result == null || prompt.isComplete) return
        result.complete(
            prompt.confirm(GeckoSession.PromptDelegate.SharePrompt.Result.FAILURE),
        )
    }

    private fun clearWebSharePending() {
        pendingWebSharePrompt = null
        pendingWebShareResult = null
    }

    fun confirmAddressSave() {
        val prompt = pendingAddressSavePrompt ?: return
        pendingAddressSaveResult?.complete(prompt.options.firstOrNull()?.let(prompt::confirm) ?: prompt.dismiss())
        clearAddressSave()
    }

    fun dismissAddressSave() {
        val prompt = pendingAddressSavePrompt ?: return
        pendingAddressSaveResult?.complete(prompt.dismiss())
        clearAddressSave()
    }

    private fun clearAddressSave() {
        pendingAddressSavePrompt = null
        pendingAddressSaveResult = null
        pendingAddressSaveAddress = null
    }

    // ================================================================
    // Delegate 生成
    // ================================================================

    fun createPromptDelegate(): GeckoSession.PromptDelegate =
        object : GeckoSession.PromptDelegate {
            override fun onAlertPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.AlertPrompt,
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                pendingAlertPrompt = prompt
                pendingAlertResult = result
                return result
            }

            override fun onButtonPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.ButtonPrompt,
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                pendingButtonPrompt = prompt
                pendingButtonResult = result
                return result
            }

            override fun onTextPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.TextPrompt,
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                pendingTextPrompt = prompt
                pendingTextResult = result
                return result
            }

            override fun onChoicePrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.ChoicePrompt,
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                pendingChoicePrompt = prompt
                pendingChoiceResult = result
                return result
            }

            override fun onColorPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.ColorPrompt,
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                pendingColorPrompt = prompt
                pendingColorResult = result
                return result
            }

            override fun onDateTimePrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.DateTimePrompt,
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                pendingDateTimePrompt = prompt
                pendingDateTimeResult = result
                return result
            }

            override fun onAuthPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.AuthPrompt,
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                pendingAuthPrompt = prompt
                pendingAuthResult = result
                return result
            }

            override fun onFilePrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.FilePrompt,
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                pendingFilePrompt = prompt
                pendingFileResult = result
                return result
            }

            override fun onBeforeUnloadPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.BeforeUnloadPrompt,
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                pendingBeforeUnloadPrompt = prompt
                pendingBeforeUnloadResult = result
                return result
            }

            override fun onRepostConfirmPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.RepostConfirmPrompt,
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                pendingRepostConfirmPrompt = prompt
                pendingRepostConfirmResult = result
                return result
            }

            override fun onSharePrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.SharePrompt,
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                if (!hasWebShareContent(prompt.title, prompt.text, prompt.uri)) {
                    return GeckoResult.fromValue(
                        prompt.confirm(GeckoSession.PromptDelegate.SharePrompt.Result.FAILURE),
                    )
                }
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                pendingWebSharePrompt = prompt
                pendingWebShareResult = result
                webShareLaunchChannel.trySend(Unit)
                return result
            }

            override fun onPopupPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.PopupPrompt,
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                // ポップアップを許可する
                return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW))
            }

            override fun onAddressSelect(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.AutocompleteRequest<Autocomplete.AddressSelectOption>,
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                Log.i(
                    "PromptDialogState",
                    "onAddressSelect options=${prompt.options.size} kind=$focusedAutofillKind",
                )
                if (prompt.options.isEmpty()) return GeckoResult.fromValue(prompt.dismiss())
                onAddressSelectOptions?.invoke(prompt.options.toList())
                return GeckoResult.fromValue(prompt.dismiss())
            }

            override fun onAddressSave(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.AutocompleteRequest<Autocomplete.AddressSaveOption>,
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val address = prompt.options.firstOrNull()?.value
                    ?: return GeckoResult.fromValue(prompt.dismiss())
                return GeckoResult<GeckoSession.PromptDelegate.PromptResponse>().also {
                    pendingAddressSaveAddress = address
                    pendingAddressSavePrompt = prompt
                    pendingAddressSaveResult = it
                }
            }
        }
}
