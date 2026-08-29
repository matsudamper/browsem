package net.matsudamper.browser.data.crashlog

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "crash_log",
    indices = [
        Index(value = ["occurredAt"], name = "index_crash_log_occurredAt"),
    ],
)
data class CrashLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val occurredAt: Long,
    val title: String,
    val body: String,
)
