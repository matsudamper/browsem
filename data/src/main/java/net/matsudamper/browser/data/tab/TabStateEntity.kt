package net.matsudamper.browser.data.tab

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tab_state")
data class TabStateEntity(
    @PrimaryKey val tabId: String,
    val url: String,
    val sessionState: String,
    val title: String,
    val openerTabId: String,
    val themeColor: Int?,
    val sortOrder: Int,
    val isSelected: Int, // 1 = 選択中, 0 = 未選択
    val groupId: String, // TabGroupId.value を格納
)
