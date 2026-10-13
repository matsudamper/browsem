package net.matsudamper.browser.ui.settings.crash

import androidx.compose.runtime.Stable

@Stable
data class CrashLogsScreenUiState(
    val callbacks: Callbacks,
    val isLoading: Boolean,
    val typeFilters: List<TypeFilterItem>,
    val entries: List<EntryItem>,
    val showDeleteAllDialog: Boolean,
) {
    @Stable
    data class TypeFilterItem(
        val label: String,
        val isSelected: Boolean,
        val listener: Listener,
    ) {
        @Stable
        interface Listener {
            fun onClick()
        }
    }

    @Stable
    data class EntryItem(
        val id: Long,
        val type: String,
        val title: String,
        val occurredAt: Long,
        val listener: Listener,
    ) {
        @Stable
        interface Listener {
            fun onClick()
        }
    }

    interface Callbacks {
        fun onClickDeleteAll()
        fun onConfirmDeleteAll()
        fun onDismissDeleteAllDialog()
    }
}

internal object PreviewCrashLogEntryListener : CrashLogsScreenUiState.EntryItem.Listener {
    override fun onClick() = Unit
}

internal object PreviewCrashLogTypeFilterListener : CrashLogsScreenUiState.TypeFilterItem.Listener {
    override fun onClick() = Unit
}
