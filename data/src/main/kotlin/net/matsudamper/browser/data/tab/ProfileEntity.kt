package net.matsudamper.browser.data.tab

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "profile")
data class ProfileEntity(
    @PrimaryKey val profileId: String, // ProfileId.value を格納
    val name: String,
    val iconKey: String, // ProfileIcon.name を格納
    val sortOrder: Int,
    val isActive: Boolean,
)
