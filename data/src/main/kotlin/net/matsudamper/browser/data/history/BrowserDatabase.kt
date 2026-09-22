package net.matsudamper.browser.data.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.matsudamper.browser.data.ProfileId

@Database(entities = [HistoryEntry::class], version = 3, exportSchema = true)
abstract class BrowserDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao

    companion object {
        @Volatile
        private var instance: BrowserDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_history_visitedAt ON history (visitedAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_history_url_visitedAt ON history (url, visitedAt)")
            }
        }

        /** v2→v3: 履歴をプロファイル単位で分けるための profileId 列を追加。既存行はデフォルトプロファイルに属する */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE history ADD COLUMN profileId TEXT NOT NULL DEFAULT '${ProfileId.DEFAULT.value}'",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_history_profileId_visitedAt ON history (profileId, visitedAt)",
                )
            }
        }

        /** 全マイグレーション。getInstance とマイグレーションテストで共用する */
        internal val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)

        fun getInstance(context: Context): BrowserDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    BrowserDatabase::class.java,
                    "browser.db",
                ).addMigrations(*ALL_MIGRATIONS).build().also { instance = it }
            }
        }
    }
}
