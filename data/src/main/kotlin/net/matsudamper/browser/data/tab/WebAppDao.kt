package net.matsudamper.browser.data.tab

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WebAppDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWebApp(webApp: WebAppEntity)

    @Query("SELECT * FROM web_app ORDER BY createdAt ASC")
    fun observeWebApps(): Flow<List<WebAppEntity>>

    @Query("SELECT * FROM web_app ORDER BY createdAt ASC")
    suspend fun getAllWebApps(): List<WebAppEntity>

    @Query("SELECT * FROM web_app WHERE webAppId = :webAppId")
    suspend fun getWebApp(webAppId: String): WebAppEntity?

    @Query("UPDATE web_app SET title = :title WHERE webAppId = :webAppId")
    suspend fun updateTitle(webAppId: String, title: String)

    @Query("DELETE FROM web_app WHERE webAppId = :webAppId")
    suspend fun deleteWebApp(webAppId: String)
}
