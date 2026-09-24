package net.matsudamper.browser.ui.tabs

import androidx.compose.runtime.Stable
import net.matsudamper.browser.data.ProfileIcon
import net.matsudamper.browser.data.ProfileId

/** タブメニューのプロファイル切り替えアイコンと、そこから開くプロファイル管理ダイアログの状態 */
@Stable
data class ProfileSwitcherUiState(
    val activeProfileIcon: ProfileIcon,
    val profiles: List<ProfileItem>,
    val callbacks: Callbacks,
) {
    @Stable
    data class ProfileItem(
        val id: ProfileId,
        val name: String,
        val icon: ProfileIcon,
        val tabCount: Int,
        val isActive: Boolean,
        /** デフォルトプロファイルは削除できないため false */
        val isDeletable: Boolean,
        val listener: Listener,
    ) {
        @Stable
        interface Listener {
            fun onSelect()
            fun onRename(newName: String)
            fun onChangeIcon(icon: ProfileIcon)
            fun onDelete()
        }
    }

    @Stable
    interface Callbacks {
        fun onAddProfile()
    }
}
