package net.matsudamper.browser.feature.forminputautofill

import net.matsudamper.browser.data.forminput.FormInputPageKey
import net.matsudamper.browser.feature.addressautofill.AddressAutofillHost

/**
 * フォーム入力の候補バーと手動保存ダイアログをホストする。
 */
interface FormInputAutofillHost : AddressAutofillHost {
    fun showFormInputSaveDialog(request: FormInputSaveDialogRequest)

    fun dismissFormInputSaveDialog()
}

data class FormInputSaveDialogRequest(
    val pageKey: FormInputPageKey,
    val fields: List<FormInputSaveFieldOption>,
    val onConfirm: (selectedFieldKeys: Set<String>) -> Unit,
    val onDismiss: () -> Unit,
)

data class FormInputSaveFieldOption(
    val fieldKey: String,
    val value: String,
    val initiallySelected: Boolean,
)
