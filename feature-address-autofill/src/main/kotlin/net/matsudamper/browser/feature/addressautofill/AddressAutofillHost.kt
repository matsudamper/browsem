package net.matsudamper.browser.feature.addressautofill

import kotlinx.coroutines.CoroutineScope
import org.mozilla.geckoview.Autocomplete

/**
 * 住所自動入力の候補表示をホストする。
 * UI は app 側に置き、機能モジュールはこの接点だけを使う。
 */
interface AddressAutofillHost {
    var focusedAutofillKind: String?
    var onAddressSelectOptions: ((List<Autocomplete.AddressSelectOption>) -> Unit)?
    val coroutineScope: CoroutineScope

    fun showAddressAutofillBar(items: List<AddressAutofillSuggestionItem>)
    fun hideAddressAutofillBar()
}

data class AddressAutofillSuggestionItem(
    val label: String,
    val kind: AddressAutofillSuggestionKind,
    val onClick: () -> Unit,
)
