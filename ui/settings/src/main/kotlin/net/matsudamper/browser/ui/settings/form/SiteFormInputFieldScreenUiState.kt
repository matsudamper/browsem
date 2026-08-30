package net.matsudamper.browser.ui.settings.form

import androidx.compose.runtime.Stable

@Stable
data class SiteFormInputFieldScreenUiState(
    val callbacks: Callbacks,
    val displayOrigin: String,
    val path: String,
    val displayPath: String,
    val fieldKey: String,
    val values: List<String>,
    val deleteValueConfirm: String?,
) {
    interface Callbacks {
        fun navigateBack()
        fun requestDeleteValue(value: String)
        fun confirmDeleteValue()
        fun dismissDeleteValueConfirm()
    }
}
