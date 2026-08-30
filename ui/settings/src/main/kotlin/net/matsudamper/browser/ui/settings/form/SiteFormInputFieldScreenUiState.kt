package net.matsudamper.browser.ui.settings.form

import androidx.compose.runtime.Stable

@Stable
data class SiteFormInputFieldScreenUiState(
    val callbacks: Callbacks,
    val displayPath: String,
    val fieldKey: String,
    val values: List<String>,
    val deleteValueConfirm: String?,
    val deleteFieldConfirm: Boolean,
) {
    interface Callbacks {
        fun navigateBack()
        fun requestDeleteValue(value: String)
        fun confirmDeleteValue()
        fun dismissDeleteValueConfirm()
        fun requestDeleteField()
        fun confirmDeleteField()
        fun dismissDeleteFieldConfirm()
    }
}
