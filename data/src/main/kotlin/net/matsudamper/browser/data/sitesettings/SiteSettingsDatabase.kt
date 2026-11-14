package net.matsudamper.browser.data.sitesettings

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SiteSettingsEntity::class,
        SiteSettingsMigrationEntity::class,
    ],
    version = SiteSettingsDatabase.SCHEMA_VERSION,
    exportSchema = true,
)
internal abstract class SiteSettingsDatabase : RoomDatabase() {
    abstract fun siteSettingsDao(): SiteSettingsDao

    companion object {
        const val SCHEMA_VERSION: Int = 2

        @Volatile
        private var instance: SiteSettingsDatabase? = null

        /** v1→v2: カメラの扱いと要求済みフラグの列を追加する */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE site_settings ADD COLUMN camera INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE site_settings ADD COLUMN cameraRequested INTEGER NOT NULL DEFAULT 0")
            }
        }

        internal val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)

        fun getInstance(context: Context): SiteSettingsDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SiteSettingsDatabase::class.java,
                    "site_settings.db",
                ).addMigrations(*ALL_MIGRATIONS).build().also { instance = it }
            }
        }
    }
}
