package net.matsudamper.browser.data.tab

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tab_group")
data class TabGroupEntity(
    @PrimaryKey val groupId: String, // TabGroupId.value を格納
    val name: String,
    val sortOrder: Int,
)
