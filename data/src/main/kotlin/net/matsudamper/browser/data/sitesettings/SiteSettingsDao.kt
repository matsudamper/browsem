package net.matsudamper.browser.data.sitesettings

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
internal interface SiteSettingsDao {
    @Query("SELECT * FROM site_settings WHERE host = :host LIMIT 1")
    fun observe(host: String): Flow<SiteSettingsEntity?>

    @Query("SELECT host FROM site_settings ORDER BY host")
    fun observeHosts(): Flow<List<String>>

    @Query(
        "SELECT host FROM site_settings " +
            "WHERE instr(lower(host), lower(:query)) > 0 " +
            "ORDER BY host LIMIT :limit OFFSET :offset",
    )
    fun observeHosts(query: String, limit: Int, offset: Int): Flow<List<String>>

    @Query("SELECT * FROM site_settings ORDER BY host")
    fun observeAll(): Flow<List<SiteSettingsEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM site_settings_migration WHERE id = 1)")
    suspend fun isLegacyMigrationCompleted(): Boolean

    @Upsert
    suspend fun upsertAll(settings: List<SiteSettingsEntity>)

    @Query("INSERT OR IGNORE INTO site_settings_migration (id) VALUES (1)")
    suspend fun markLegacyMigrationCompleted()

    @Query(
        "INSERT OR IGNORE INTO site_settings " +
            "(host, microphone, microphoneRequested, geolocation, geolocationRequested, autoplay, autoplayRequested) " +
            "VALUES (:host, 0, 0, 0, 0, 0, 0)",
    )
    suspend fun ensureHost(host: String)

    @Query("UPDATE site_settings SET microphone = :state WHERE host = :host")
    suspend fun updateMicrophonePermission(host: String, state: Int)

    @Query("UPDATE site_settings SET microphoneRequested = 1 WHERE host = :host")
    suspend fun markMicrophonePermissionRequested(host: String)

    @Query("UPDATE site_settings SET autoplay = :state WHERE host = :host")
    suspend fun updateAutoplayPermission(host: String, state: Int)

    @Query("UPDATE site_settings SET autoplayRequested = 1 WHERE host = :host")
    suspend fun markAutoplayPermissionRequested(host: String)

    @Query("UPDATE site_settings SET geolocation = :state WHERE host = :host")
    suspend fun updateGeolocationState(host: String, state: Int)

    @Query("UPDATE site_settings SET geolocationRequested = 1 WHERE host = :host")
    suspend fun markGeolocationRequested(host: String)
}
