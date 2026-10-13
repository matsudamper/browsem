package net.matsudamper.browser.data.crashlog

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [CrashLogEntity::class],
    version = CrashLogDatabase.SCHEMA_VERSION,
    exportSchema = true,
)
abstract class CrashLogDatabase : RoomDatabase() {
    abstract fun crashLogDao(): CrashLogDao

    companion object {
        const val SCHEMA_VERSION: Int = 2

        @Volatile
        private var instance: CrashLogDatabase? = null

        /** v1→v2: 種別を文字列で持つ type 列を追加。既存の INFO 診断ログは Log、それ以外は Crash */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val legacyInfoPrefix = "INFO: "
                val titleWithoutPrefixStart = legacyInfoPrefix.length + 1
                db.execSQL(
                    "ALTER TABLE crash_log ADD COLUMN type TEXT NOT NULL DEFAULT '${CrashLogRepository.TYPE_CRASH}'",
                )
                db.execSQL(
                    "UPDATE crash_log SET type = '${CrashLogRepository.TYPE_LOG}' WHERE title LIKE '$legacyInfoPrefix%'",
                )
                db.execSQL(
                    "UPDATE crash_log SET title = substr(title, $titleWithoutPrefixStart) WHERE title LIKE '$legacyInfoPrefix%'",
                )
            }
        }

        internal val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)

        fun getInstance(context: Context): CrashLogDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    CrashLogDatabase::class.java,
                    "crash_log.db",
                )
                    .addMigrations(*ALL_MIGRATIONS)
                    // クラッシュハンドラはメインスレッドから同期保存するため許可する
                    .allowMainThreadQueries()
                    .build().also { instance = it }
            }
        }

        fun closeInstance() {
            val toClose = synchronized(this) {
                val current = instance
                instance = null
                current
            }
            toClose?.close()
        }
    }
}
