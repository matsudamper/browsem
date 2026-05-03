package net.matsudamper.browser.data.history

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "history",
    indices = [
        // ORDER BY visitedAt DESC を高速化
        Index(value = ["visitedAt"]),
        // 相関サブクエリの WHERE url = h.url ORDER BY visitedAt DESC を高速化
        Index(value = ["url", "visitedAt"]),
    ],
)
data class HistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val visitedAt: Long,
)
