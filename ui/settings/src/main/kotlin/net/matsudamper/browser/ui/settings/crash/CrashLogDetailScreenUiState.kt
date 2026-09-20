package net.matsudamper.browser.ui.settings.crash

import androidx.compose.runtime.Stable

@Stable
data class CrashLogDetailScreenUiState(
    val callbacks: Callbacks,
    val isLoading: Boolean,
    val entry: Entry?,
) {
    @Stable
    data class Entry(
        val occurredAt: Long,
        val title: String,
        val body: String,
    )

    interface Callbacks {
        fun onClickCopyBody()
    }
}
