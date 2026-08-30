package net.matsudamper.browser.ui.settings.form

import androidx.compose.runtime.Stable

@Stable
data class SiteFormInputPathScreenUiState(
    val callbacks: Callbacks,
    val path: String,
    val displayPath: String,
    val fields: List<FieldEntry>,
    val deletePathConfirm: Boolean,
    val deleteFieldConfirm: String?,
) {
    @Stable
    data class FieldEntry(
        val fieldKey: String,
        val previewText: String,
    )

    interface Callbacks {
        fun navigateBack()
        fun openField(fieldKey: String)
        fun requestDeleteField(fieldKey: String)
        fun confirmDeleteField()
        fun dismissDeleteFieldConfirm()
        fun requestDeletePath()
        fun confirmDeletePath()
        fun dismissDeletePathConfirm()
    }
}
