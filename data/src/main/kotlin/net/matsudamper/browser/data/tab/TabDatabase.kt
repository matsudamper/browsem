package net.matsudamper.browser.data.tab

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File

@Database(
    entities = [TabStateEntity::class, TabGroupEntity::class],
    version = TabDatabase.SCHEMA_VERSION,
    exportSchema = true,
)
abstract class TabDatabase : RoomDatabase() {
    abstract fun tabDao(): TabDao
    abstract fun tabGroupDao(): TabGroupDao

    companion object {
        /** Room の @Database version と連動。バックアップ互換性チェックでも参照する */
        const val SCHEMA_VERSION: Int = 4

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

        private const val SESSION_STATE_CHUNK_CHARS = 256 * 1024

        /**
         * v3→v4: tab_state から sessionState カラムを削除する。
         * 削除前に、まだファイルへ移行されていない sessionState をファイルへコピーする。
         * CursorWindow 上限(約2MB)を超え得るため substr で分割読みする。
         * 既にアプリ側で移行済み（ファイルが存在する）タブはスキップする。
         */
        internal fun createMigration3To4(sessionStateDir: File): Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                migrateSessionStatesToFiles(db, sessionStateDir)
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tab_state_new` (" +
                        "`tabId` TEXT NOT NULL, `url` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                        "`openerTabId` TEXT NOT NULL, `themeColor` INTEGER, `sortOrder` INTEGER NOT NULL, " +
                        "`isSelected` INTEGER NOT NULL, `groupId` TEXT NOT NULL, PRIMARY KEY(`tabId`))",
                )
                db.execSQL(
                    "INSERT INTO `tab_state_new` " +
                        "(`tabId`, `url`, `title`, `openerTabId`, `themeColor`, `sortOrder`, `isSelected`, `groupId`) " +
                        "SELECT `tabId`, `url`, `title`, `openerTabId`, `themeColor`, `sortOrder`, `isSelected`, `groupId` " +
                        "FROM `tab_state`",
                )
                db.execSQL("DROP TABLE `tab_state`")
                db.execSQL("ALTER TABLE `tab_state_new` RENAME TO `tab_state`")
            }
        }

        private fun migrateSessionStatesToFiles(db: SupportSQLiteDatabase, sessionStateDir: File) {
            if (!sessionStateDir.exists()) sessionStateDir.mkdirs()
            db.query(
                "SELECT tabId, length(sessionState) FROM tab_state " +
                    "WHERE sessionState IS NOT NULL AND length(sessionState) > 0",
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val tabId = cursor.getString(0)
                    val length = cursor.getInt(1)
                    if (File(sessionStateDir, tabId).exists()) continue
                    try {
                        val builder = StringBuilder(length)
                        var offset = 1
                        while (offset <= length) {
                            db.query(
                                "SELECT substr(sessionState, ?, ?) FROM tab_state WHERE tabId = ?",
                                arrayOf<Any?>(offset, SESSION_STATE_CHUNK_CHARS, tabId),
                            ).use { chunkCursor ->
                                if (chunkCursor.moveToFirst()) {
                                    chunkCursor.getString(0)?.let { builder.append(it) }
                                }
                            }
                            offset += SESSION_STATE_CHUNK_CHARS
                        }
                        if (builder.isNotEmpty()) {
                            File(sessionStateDir, tabId).writeText(builder.toString())
                        }
                    } catch (_: Exception) {
                        // ファイル書き込み失敗時はスキップ（セッション状態は失われるがアプリは起動できる）
                    }
                }
            }
        }

        /** 全マイグレーション。getInstance とマイグレーションテストで共用する */
        internal fun allMigrations(sessionStateDir: File): Array<Migration> = arrayOf(
            MIGRATION_1_2, MIGRATION_2_3, createMigration3To4(sessionStateDir),
        )

        fun getInstance(context: Context): TabDatabase {
            instance?.takeIf { it.isOpen }?.let { return it }
            return synchronized(this) {
                instance?.takeIf { it.isOpen } ?: run {
                    val sessionStateDir = File(context.filesDir, "tab_session_states")
                    Room.databaseBuilder(
                        context.applicationContext,
                        TabDatabase::class.java,
                        "tab.db",
                    )
                        .addMigrations(*allMigrations(sessionStateDir))
                        .build().also { instance = it }
                }
            }
        }

        /**
         * シングルトンを閉じてキャッシュを破棄する。バックアップから tab.db を
         * 上書き復元する直前など、Room の接続を解放したい場面で使う。
         * 呼び出し後すぐに次の Room アクセスがあると新しいインスタンスが
         * 復元後のファイルを開くので、復元完了→プロセス終了の流れで使うこと。
         */
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
