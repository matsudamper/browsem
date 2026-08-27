package net.matsudamper.browser.data.forminput

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FormInputDao {
    @Insert
    suspend fun insert(entity: FormFieldValueEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPreference(entity: FormInputPreferenceEntity)

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

    @Query(
        """
        SELECT path, COUNT(DISTINCT fieldKey) AS fieldCount
        FROM form_field_value
        WHERE host = :host
        GROUP BY path
        ORDER BY path
        """,
    )
    fun observePathCounts(host: String): Flow<List<FormPathCountRow>>

    @Query(
        """
        SELECT * FROM form_input_preference
        WHERE host = :host
        """,
    )
    fun observePreferencesForHost(host: String): Flow<List<FormInputPreferenceEntity>>

    @Query(
        """
        SELECT * FROM form_input_preference
        WHERE host = :host AND path = :path
        """,
    )
    fun observePreferencesForPath(host: String, path: String): Flow<List<FormInputPreferenceEntity>>

    @Query(
        """
        SELECT fieldKey FROM form_field_value
        WHERE host = :host AND path = :path
        GROUP BY fieldKey
        ORDER BY fieldKey
        """,
    )
    fun observeDistinctFieldKeys(host: String, path: String): Flow<List<String>>

    @Query(
        """
        SELECT COUNT(DISTINCT fieldKey) FROM form_field_value
        WHERE host = :host AND path = :path
        """,
    )
    suspend fun countFieldsForPath(host: String, path: String): Int

    @Query(
        """
        SELECT COUNT(DISTINCT path) FROM form_field_value
        WHERE host = :host
        """,
    )
    fun observePathCount(host: String): Flow<Int>

    @Query(
        """
        SELECT enabled FROM form_input_preference
        WHERE host = :host AND path = :path AND fieldKey = :fieldKey
        """,
    )
    suspend fun getPreferenceEnabled(host: String, path: String, fieldKey: String): Boolean?

    @Query(
        """
        DELETE FROM form_field_value
        WHERE host = :host AND path = :path
        """,
    )
    suspend fun deleteValuesForPath(host: String, path: String)

    @Query(
        """
        DELETE FROM form_field_value
        WHERE host = :host AND path = :path AND fieldKey = :fieldKey
        """,
    )
    suspend fun deleteValuesForField(host: String, path: String, fieldKey: String)

    @Query(
        """
        DELETE FROM form_input_preference
        WHERE host = :host AND path = :path
        """,
    )
    suspend fun deletePreferencesForPath(host: String, path: String)

    @Query(
        """
        DELETE FROM form_input_preference
        WHERE host = :host AND path = :path AND fieldKey = :fieldKey
        """,
    )
    suspend fun deletePreferenceForField(host: String, path: String, fieldKey: String)

    @Query("DELETE FROM form_field_value")
    suspend fun deleteAllValues()

    @Query("DELETE FROM form_input_preference")
    suspend fun deleteAllPreferences()
}
