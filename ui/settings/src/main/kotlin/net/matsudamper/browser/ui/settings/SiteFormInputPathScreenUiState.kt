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
    val deletePathConfirm: Boolean,
) {
    @Stable
    data class FieldEntry(
        val fieldKey: String,
        val valueCount: Int,
        val enabled: Boolean,
    )

    interface Callbacks {
        fun navigateBack()
        fun setPathEnabled(enabled: Boolean)
        fun setFieldEnabled(fieldKey: String, enabled: Boolean)
        fun openField(fieldKey: String)
        fun requestDeletePath()
        fun confirmDeletePath()
        fun dismissDeletePathConfirm()
    }
}
