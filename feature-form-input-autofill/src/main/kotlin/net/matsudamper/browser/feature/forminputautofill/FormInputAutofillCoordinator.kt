package net.matsudamper.browser.feature.forminputautofill

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.matsudamper.browser.data.forminput.FormFieldEntry
import net.matsudamper.browser.data.forminput.FormInputPageKey
import net.matsudamper.browser.data.forminput.FormInputRepository
import net.matsudamper.browser.data.forminput.parseFormInputPageKey
import net.matsudamper.browser.feature.addressautofill.AddressAutofillSuggestionItem
import net.matsudamper.browser.feature.addressautofill.AddressAutofillSuggestionKind
import org.mozilla.geckoview.GeckoSession

/**
 * ページ (host + path) 単位でフォーム送信内容を保存し、
 * 住所自動入力とは別に候補バーへサジェストを出す。
 */
class FormInputAutofillCoordinator(
    private val fillExtension: FormInputAutofillWebExtension,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Any()
    private var attached: Attached? = null
    private var showJob: Job? = null
    private var hideJob: Job? = null
    private var lastFieldKey: String? = null
    private var lastPageKey: FormInputPageKey? = null
    private var focusGeneration: Int = 0
    private var suppressFocusUntilElapsed: Long = 0L
    private var suppressFocusKey: String? = null

    private class Attached(
        val session: GeckoSession,
        val host: FormInputAutofillHost,
        val formInputRepository: FormInputRepository,
    )

    fun attach(
        session: GeckoSession,
        host: FormInputAutofillHost,
        formInputRepository: FormInputRepository,
    ) {
        synchronized(lock) {
            showJob?.cancel()
            showJob = null
            hideJob?.cancel()
            hideJob = null
            attached = Attached(session, host, formInputRepository)
        }
        fillExtension.registerSession(
            session,
            object : FormInputAutofillWebExtension.SessionListener {
                override fun onFieldFocus(fieldKey: String, pageUrl: String) {
                    handleFieldFocus(fieldKey, pageUrl)
                }

                override fun onFieldBlur() {
                    handleFieldBlur()
                }

                override fun onFormSubmit(pageUrl: String, fields: List<FormInputFieldMessage>) {
                    handleFormSubmit(pageUrl, fields)
                }

                override fun onFieldLongPress(
                    fieldKey: String,
                    pageUrl: String,
                    fields: List<FormInputFieldMessage>,
                ) {
                    handleFieldLongPress(fieldKey, pageUrl, fields)
                }

                override fun onFocusPortDisconnected() {
                    handleFocusPortDisconnected()
                }
            },
        )
    }

    fun detach(session: GeckoSession) {
        fillExtension.unregisterSession(session)
        synchronized(lock) {
            if (attached?.session !== session) return
            showJob?.cancel()
            showJob = null
            hideJob?.cancel()
            hideJob = null
            focusGeneration += 1
            attached?.host?.hideAddressAutofillBar()
            attached = null
            lastFieldKey = null
            lastPageKey = null
        }
    }

    /**
     * テキスト選択メニュー（コピー/ペースト等）から呼び出し、
     * フォーカス中の入力欄を保存対象にする確認ダイアログを表示する。
     */
    fun requestSaveFocusedField(session: GeckoSession) {
        val current = synchronized(lock) { attached } ?: return
        if (current.session !== session) return
        fillExtension.queryFocusedField(session) { field, pageUrl ->
            if (field == null || pageUrl.isNullOrBlank()) return@queryFocusedField
            handleFieldLongPress(field.fieldKey, pageUrl, listOf(field))
        }
    }

    private fun handleFieldFocus(fieldKey: String, pageUrl: String) {
        val pageKey = parseFormInputPageKey(pageUrl) ?: return
        if (fieldKey.isBlank()) return
        if (isFocusSuppressed(fieldKey)) {
            Log.i(TAG, "field-focus ignored after fill key=$fieldKey")
            return
        }
        val current = synchronized(lock) {
            val host = attached?.host
            if (host != null) {
                host.autofillBarHideGeneration += 1
                host.hideAddressAutofillBar()
            }
            lastFieldKey = fieldKey
            lastPageKey = pageKey
            focusGeneration += 1
            hideJob?.cancel()
            hideJob = null
            attached
        } ?: return
        Log.i(TAG, "field-focus key=$fieldKey host=${pageKey.host} path=${pageKey.path}")
        synchronized(lock) {
            showJob?.cancel()
            showJob = current.host.coroutineScope.launch {
                scheduleSuggestionBar(
                    repository = current.formInputRepository,
                    pageKey = pageKey,
                    fieldKey = fieldKey,
                    shouldAbort = {
                        synchronized(lock) {
                            lastFieldKey != fieldKey ||
                                lastPageKey != pageKey ||
                                isFocusSuppressed(fieldKey)
                        }
                    },
                    present = { values ->
                        presentCompletions(
                            session = current.session,
                            fieldKey = fieldKey,
                            values = values,
                        )
                    },
                )
            }
        }
    }

    private fun handleFormSubmit(pageUrl: String, fields: List<FormInputFieldMessage>) {
        val pageKey = parseFormInputPageKey(pageUrl) ?: return
        val current = synchronized(lock) { attached } ?: return
        if (fields.isEmpty()) return
        current.host.coroutineScope.launch(ioDispatcher) {
            runCatching {
                current.formInputRepository.saveFields(
                    pageKey = pageKey,
                    fields = fields.map { FormFieldEntry(fieldKey = it.fieldKey, value = it.value) },
                )
                Log.i(TAG, "form-submit saved host=${pageKey.host} path=${pageKey.path} count=${fields.size}")
            }.onFailure { error ->
                Log.w(TAG, "form-submit save failed", error)
            }
        }
    }

    private fun handleFieldLongPress(
        fieldKey: String,
        pageUrl: String,
        fields: List<FormInputFieldMessage>,
    ) {
        val pageKey = parseFormInputPageKey(pageUrl) ?: return
        if (fieldKey.isBlank() || fields.isEmpty()) return
        val current = synchronized(lock) { attached } ?: return
        val pressedField = fields
            .firstOrNull { it.fieldKey == fieldKey }
            ?: fields.distinctBy { it.fieldKey }.firstOrNull { it.fieldKey.isNotBlank() }
            ?: return
        current.host.coroutineScope.launch(ioDispatcher) {
            withContext(Dispatchers.Main) {
                current.host.showFormInputSaveDialog(
                    FormInputSaveDialogRequest(
                        pageKey = pageKey,
                        fieldKey = pressedField.fieldKey,
                        value = pressedField.value,
                        onConfirm = {
                            current.host.dismissFormInputSaveDialog()
                            current.host.coroutineScope.launch(ioDispatcher) {
                                runCatching {
                                    current.formInputRepository.registerFieldAndSave(
                                        pageKey = pageKey,
                                        fields = listOf(
                                            FormFieldEntry(
                                                fieldKey = pressedField.fieldKey,
                                                value = pressedField.value,
                                            ),
                                        ),
                                    )
                                    Log.i(
                                        TAG,
                                        "field-long-press saved host=${pageKey.host} path=${pageKey.path} " +
                                            "field=${pressedField.fieldKey}",
                                    )
                                }.onFailure { error ->
                                    Log.w(TAG, "field-long-press save failed", error)
                                }
                            }
                        },
                        onDismiss = {},
                    ),
                )
            }
        }
    }

    private fun handleFieldBlur() {
        synchronized(lock) {
            val current = attached ?: return
            showJob?.cancel()
            showJob = null
            lastFieldKey = null
            lastPageKey = null
            val hideGeneration = current.host.autofillBarHideGeneration
            hideJob?.cancel()
            hideJob = current.host.coroutineScope.launch {
                delay(FORM_INPUT_BLUR_HIDE_WAIT_MS)
                val host = synchronized(lock) {
                    if (attached?.host?.autofillBarHideGeneration != hideGeneration) return@launch
                    attached?.host
                } ?: return@launch
                host.hideAddressAutofillBar()
            }
        }
    }

    private fun handleFocusPortDisconnected() {
        synchronized(lock) {
            showJob?.cancel()
            showJob = null
            hideJob?.cancel()
            hideJob = null
            lastFieldKey = null
            lastPageKey = null
            focusGeneration += 1
            attached?.host?.hideAddressAutofillBar()
        }
        Log.i(TAG, "focus port disconnected")
    }

    private fun presentCompletions(
        session: GeckoSession,
        fieldKey: String,
        values: List<String>,
    ) {
        val current = synchronized(lock) { attached } ?: return
        val items = values.mapNotNull { value ->
            if (value.isBlank()) {
                null
            } else {
                AddressAutofillSuggestionItem(
                    label = value,
                    kind = AddressAutofillSuggestionKind.FormField,
                    onClick = {
                        fillSelectedValue(session, fieldKey, value)
                    },
                )
            }
        }
        if (items.isEmpty()) {
            current.host.hideAddressAutofillBar()
            return
        }
        Log.i(TAG, "suggestion bar key=$fieldKey count=${items.size}")
        current.host.showAddressAutofillBar(items)
    }

    private fun fillSelectedValue(session: GeckoSession, fieldKey: String, value: String) {
        synchronized(lock) {
            showJob?.cancel()
            showJob = null
            hideJob?.cancel()
            hideJob = null
            focusGeneration += 1
            suppressFocusUntilElapsed = SystemClock.elapsedRealtime() + FILL_FOCUS_SUPPRESS_MS
            suppressFocusKey = fieldKey
            attached?.host?.hideAddressAutofillBar()
        }
        fillExtension.fill(session, fieldKey, value)
    }

    private fun isFocusSuppressed(fieldKey: String): Boolean {
        if (SystemClock.elapsedRealtime() >= suppressFocusUntilElapsed) return false
        return suppressFocusKey == fieldKey
    }

    private suspend fun scheduleSuggestionBar(
        repository: FormInputRepository,
        pageKey: FormInputPageKey,
        fieldKey: String,
        shouldAbort: () -> Boolean,
        present: (List<String>) -> Unit,
    ) {
        delay(FORM_INPUT_IME_READY_WAIT_MS)
        if (shouldAbort()) return
        val values = withContext(ioDispatcher) {
            repository.getSuggestions(pageKey = pageKey, fieldKey = fieldKey)
        }
        if (values.isEmpty()) {
            synchronized(lock) { attached?.host?.hideAddressAutofillBar() }
            return
        }
        if (shouldAbort()) return
        present(values)
    }

    companion object {
        private const val TAG = "FormInputAutofill"
        private const val FILL_FOCUS_SUPPRESS_MS = 1_500L
        private const val FORM_INPUT_IME_READY_WAIT_MS = 150L
        private const val FORM_INPUT_BLUR_HIDE_WAIT_MS = 300L
    }
}
