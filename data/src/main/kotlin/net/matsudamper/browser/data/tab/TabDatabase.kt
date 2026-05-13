package net.matsudamper.browser.data.tab

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [TabStateEntity::class, TabGroupEntity::class], version = 3, exportSchema = false)
abstract class TabDatabase : RoomDatabase() {
    abstract fun tabDao(): TabDao
    abstract fun tabGroupDao(): TabGroupDao

    companion object {
        @Volatile
        private var instance: TabDatabase? = null

        /** v1→v2: tab_group テーブルの追加と tab_state への groupId カラム追加 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tab_group` (`groupId` TEXT NOT NULL, `name` TEXT NOT NULL, `sortOrder` INTEGER NOT NULL, PRIMARY KEY(`groupId`))",
                )
                db.execSQL("ALTER TABLE `tab_state` ADD COLUMN `groupId` TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v2→v3: tab_group への isDefault カラム追加 */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tab_group` ADD COLUMN `isDefault` INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getInstance(context: Context): TabDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    TabDatabase::class.java,
                    "tab.db",
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build().also { instance = it }
            }
        }

        /**
         * シングルトンを閉じてキャッシュを破棄する。バックアップから tab.db を
         * 上書き復元する直前など、Room の接続を解放したい場面で使う。
         * 呼び出し後すぐに次の Room アクセスがあると新しいインスタンスが
         * 復元後のファイルを開くので、復元完了→プロセス終了の流れで使うこと。
         */
        fun closeInstance() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }
    }
}
