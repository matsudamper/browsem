package net.matsudamper.browser.ui.settings

import androidx.compose.runtime.Stable

@Stable
data class SiteFormInputPathsScreenUiState(
    val callbacks: Callbacks,
    val host: String,
    val paths: List<PathEntry>,
) {
    @Stable
    data class PathEntry(
        val path: String,
        val displayPath: String,
        val fieldCount: Int,
        val enabled: Boolean,
    )

    interface Callbacks {
        fun navigateBack()
        fun setPathEnabled(path: String, enabled: Boolean)
        fun openPath(path: String)
    }
}
