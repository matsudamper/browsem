package net.matsudamper.browser.data.forminput

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "form_field_value",
    indices = [
        Index(value = ["scheme", "host", "port", "path", "fieldKey", "createdAt"]),
    ],
)
data class FormFieldValueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scheme: String,
    val host: String,
    val port: Int,
    val path: String,
    val fieldKey: String,
    val value: String,
    val createdAt: Long = System.currentTimeMillis(),
)
