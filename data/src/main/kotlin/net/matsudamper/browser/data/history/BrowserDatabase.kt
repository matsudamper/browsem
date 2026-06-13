package net.matsudamper.browser.data.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [HistoryEntry::class], version = 2, exportSchema = true)
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

        /** 全マイグレーション。getInstance とマイグレーションテストで共用する */
        internal val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)

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
