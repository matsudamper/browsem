package net.matsudamper.browser.data.forminput

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface FormInputDao {
    @Insert
    suspend fun insert(entity: FormFieldValueEntity): Long

    @Query(
        """
        SELECT value FROM form_field_value
        WHERE host = :host AND path = :path AND fieldKey = :fieldKey AND value != ''
        GROUP BY value
        ORDER BY MAX(createdAt) DESC
        LIMIT :limit
        """,
    )
    suspend fun getDistinctValuesForField(
        host: String,
        path: String,
        fieldKey: String,
        limit: Int,
    ): List<String>

    @Query("DELETE FROM form_field_value")
    suspend fun deleteAll()
}
