package net.matsudamper.browser.ui.tabs

import androidx.compose.runtime.Stable
import net.matsudamper.browser.data.TabGroupData

@Stable
data class TabsScreenUiState(
    val callbacks: Callbacks,
    val loadingState: LoadingState,
    val pendingClosedTab: PendingClosedTab? = null,
) {
    data class PendingClosedTab(
        val tabId: String,
        val title: String,
    )

    interface Callbacks {
        fun onUndoCloseTab()
        fun onConfirmCloseTab()
        fun onReorderTabs(groupIndex: Int, fromLocalIndex: Int, toLocalIndex: Int)
        fun onReorderGroups(fromIndex: Int, toIndex: Int)
        fun onGroupSelected(index: Int)
        fun onGroupPageChanged(page: Int)
        fun onAddGroup()
        fun onRenameGroup(groupIndex: Int, newName: String)
        fun onDeleteGroup(groupIndex: Int)
        fun onToggleDefaultGroup(groupIndex: Int)
    }

    sealed interface LoadingState {
        object Loading : LoadingState
        data class Loaded(
            val groupedTabs: List<List<TabsScreenTabData>>,
            val groups: List<TabGroupData>,
            val activeGroupIndex: Int,
            val selectedTabId: String?,
            val groupHasPlayingTab: List<Boolean> = emptyList(),
            val onOpenNewTab: () -> Unit,
        ) : LoadingState
    }
}

@Stable
data class TabsScreenTabData(
    val id: String,
    val title: String,
    val previewImage: TabPreviewImage?,
    val isPlaying: Boolean = false,
    val onSelect: () -> Unit,
    val onClose: () -> Unit,
    val onMoveToGroup: (targetGroupIndex: Int) -> Unit,
)

// ByteArray を contentEquals で比較するラッパー。
// data class の自動生成 equals は配列の参照等値になるため、
// ラッパー側で正しいコンテンツ等値を実装する。
class TabPreviewImage(val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TabPreviewImage) return false
        return bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = bytes.contentHashCode()
}

internal fun previewTabData(
    id: String,
    title: String,
    previewImage: TabPreviewImage? = null,
    isPlaying: Boolean = false,
): TabsScreenTabData {
    return TabsScreenTabData(
        id = id,
        title = title,
        previewImage = previewImage,
        isPlaying = isPlaying,
        onSelect = {},
        onClose = {},
        onMoveToGroup = {},
    )
}
