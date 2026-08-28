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
        WHERE scheme = :scheme AND host = :host AND port = :port
          AND path = :path AND fieldKey = :fieldKey AND value != ''
        GROUP BY value
        ORDER BY MAX(createdAt) DESC
        LIMIT :limit
        """,
    )
    suspend fun getDistinctValuesForField(
        scheme: String,
        host: String,
        port: Int,
        path: String,
        fieldKey: String,
        limit: Int,
    ): List<String>

    @Query(
        """
        SELECT path, COUNT(DISTINCT fieldKey) AS fieldCount
        FROM form_field_value
        WHERE scheme = :scheme AND host = :host AND port = :port
        GROUP BY path
        ORDER BY path
        """,
    )
    fun observePathCounts(
        scheme: String,
        host: String,
        port: Int,
    ): Flow<List<FormPathCountRow>>

    @Query(
        """
        SELECT * FROM form_input_preference
        WHERE scheme = :scheme AND host = :host AND port = :port
        """,
    )
    fun observePreferencesForOrigin(
        scheme: String,
        host: String,
        port: Int,
    ): Flow<List<FormInputPreferenceEntity>>

    @Query(
        """
        SELECT * FROM form_input_preference
        WHERE scheme = :scheme AND host = :host AND port = :port AND path = :path
        """,
    )
    fun observePreferencesForPath(
        scheme: String,
        host: String,
        port: Int,
        path: String,
    ): Flow<List<FormInputPreferenceEntity>>

    @Query(
        """
        SELECT fieldKey FROM form_field_value
        WHERE scheme = :scheme AND host = :host AND port = :port AND path = :path
        GROUP BY fieldKey
        ORDER BY fieldKey
        """,
    )
    fun observeDistinctFieldKeys(
        scheme: String,
        host: String,
        port: Int,
        path: String,
    ): Flow<List<String>>

    @Query(
        """
        SELECT * FROM form_field_value
        WHERE scheme = :scheme AND host = :host AND port = :port AND path = :path
        ORDER BY createdAt DESC
        """,
    )
    fun observeValuesForPath(
        scheme: String,
        host: String,
        port: Int,
        path: String,
    ): Flow<List<FormFieldValueEntity>>

    @Query(
        """
        SELECT COUNT(DISTINCT fieldKey) FROM form_field_value
        WHERE scheme = :scheme AND host = :host AND port = :port AND path = :path
        """,
    )
    suspend fun countFieldsForPath(
        scheme: String,
        host: String,
        port: Int,
        path: String,
    ): Int

    @Query(
        """
        SELECT COUNT(DISTINCT path) FROM form_field_value
        WHERE scheme = :scheme AND host = :host AND port = :port
        """,
    )
    fun observePathCount(
        scheme: String,
        host: String,
        port: Int,
    ): Flow<Int>

    @Query(
        """
        SELECT enabled FROM form_input_preference
        WHERE scheme = :scheme AND host = :host AND port = :port
          AND path = :path AND fieldKey = :fieldKey
        """,
    )
    suspend fun getPreferenceEnabled(
        scheme: String,
        host: String,
        port: Int,
        path: String,
        fieldKey: String,
    ): Boolean?

    @Query(
        """
        DELETE FROM form_field_value
        WHERE scheme = :scheme AND host = :host AND port = :port AND path = :path
        """,
    )
    suspend fun deleteValuesForPath(
        scheme: String,
        host: String,
        port: Int,
        path: String,
    )

    @Query(
        """
        DELETE FROM form_field_value
        WHERE scheme = :scheme AND host = :host AND port = :port
          AND path = :path AND fieldKey = :fieldKey
        """,
    )
    suspend fun deleteValuesForField(
        scheme: String,
        host: String,
        port: Int,
        path: String,
        fieldKey: String,
    )

    @Query(
        """
        DELETE FROM form_input_preference
        WHERE scheme = :scheme AND host = :host AND port = :port AND path = :path
        """,
    )
    suspend fun deletePreferencesForPath(
        scheme: String,
        host: String,
        port: Int,
        path: String,
    )

    @Query(
        """
        DELETE FROM form_input_preference
        WHERE scheme = :scheme AND host = :host AND port = :port
          AND path = :path AND fieldKey = :fieldKey
        """,
    )
    suspend fun deletePreferenceForField(
        scheme: String,
        host: String,
        port: Int,
        path: String,
        fieldKey: String,
    )

    @Query("DELETE FROM form_field_value")
    suspend fun deleteAllValues()

    @Query("DELETE FROM form_input_preference")
    suspend fun deleteAllPreferences()
}
