package net.matsudamper.browser.ui.settings

import androidx.compose.runtime.Stable

@Stable
data class SiteFormInputPathScreenUiState(
    val callbacks: Callbacks,
    val displayOrigin: String,
    val path: String,
    val displayPath: String,
    val pathEnabled: Boolean,
    val fields: List<FieldEntry>,
    val deleteFieldConfirm: String?,
    val deletePathConfirm: Boolean,
) {
    @Stable
    data class FieldEntry(
        val fieldKey: String,
        val previewText: String,
        val enabled: Boolean,
    )

    interface Callbacks {
        fun navigateBack()
        fun setPathEnabled(enabled: Boolean)
        fun setFieldEnabled(fieldKey: String, enabled: Boolean)
        fun requestDeleteField(fieldKey: String)
        fun confirmDeleteField()
        fun dismissDeleteFieldConfirm()
        fun requestDeletePath()
        fun confirmDeletePath()
        fun dismissDeletePathConfirm()
    }
}
