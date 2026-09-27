package net.matsudamper.browser.data.tab

import androidx.room.Entity
import androidx.room.PrimaryKey

/** ホームに「アプリとして追加」したウェブアプリ */
@Entity(tableName = "web_app")
data class WebAppEntity(
    @PrimaryKey val webAppId: String, // WebAppId.value を格納
    val profileId: String, // ProfileId.value を格納
    val startUrl: String,
    val title: String,
    val createdAt: Long,
)
