package net.matsudamper.browser.data.download

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [DownloadEntity::class], version = 4, exportSchema = false)
abstract class DownloadDatabase : RoomDatabase() {

    abstract fun downloadDao(): DownloadDao

    companion object {
        @Volatile
        private var instance: DownloadDatabase? = null

        /** バージョン1→2: referrerUrl と partialFileUri カラムを追加 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE download ADD COLUMN referrerUrl TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE download ADD COLUMN partialFileUri TEXT")
            }
        }

        /** バージョン2→3: currentWorkerId カラムを追加（既存レコードは workerId と同値で埋める） */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE download ADD COLUMN currentWorkerId TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE download SET currentWorkerId = workerId")
            }
        }

        /** バージョン3→4: currentWorkerId にインデックスを追加し、状態判定・進捗更新クエリを高速化する */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_download_currentWorkerId ON download(currentWorkerId)",
                )
            }
        }

        fun getInstance(context: Context): DownloadDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    DownloadDatabase::class.java,
                    "download.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { instance = it }
            }
        }
    }
}
