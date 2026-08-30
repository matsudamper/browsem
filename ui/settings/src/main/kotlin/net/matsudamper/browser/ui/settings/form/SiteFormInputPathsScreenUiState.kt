package net.matsudamper.browser.ui.settings.form

import androidx.compose.runtime.Stable

@Stable
data class SiteFormInputPathsScreenUiState(
    val callbacks: Callbacks,
    val displayOrigin: String,
    val paths: List<PathEntry>,
) {
    @Stable
    data class PathEntry(
        val path: String,
        val displayPath: String,
        val fieldCount: Int,
    )

    interface Callbacks {
        fun navigateBack()
        fun openPath(path: String)
    }
}