package net.matsudamper.browser.data.tab

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * TabDatabase のマイグレーションテスト。
 *
 * Room 公式手順 (https://developer.android.com/training/data-storage/room/migrating-db-versions#test)
 * に従い、エクスポート済みスキーマ JSON と [MigrationTestHelper] を使って
 * 各バージョンのスキーマを作成し、マイグレーション後にスキーマ検証とデータ保持を確認する。
 */
@RunWith(RobolectricTestRunner::class)
class TabDatabaseMigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        TabDatabase::class.java,
    )

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    /** v1→v2: tab_group テーブル追加と tab_state.groupId 追加。既存タブが保持されることを確認 */
    @Test
    fun migrate1To2() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                "INSERT INTO tab_state " +
                    "(tabId, url, sessionState, title, openerTabId, themeColor, sortOrder, isSelected) " +
                    "VALUES ('t1', 'https://example.com', 'state', 'Example', '', NULL, 0, 1)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            2,
            true,
            *TabDatabase.allMigrations(sessionStateDir()),
        )

        db.query("SELECT tabId, groupId FROM tab_state WHERE tabId = 't1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("t1", cursor.getString(0))
            // ALTER TABLE ... DEFAULT '' により既存行の groupId は空文字になる
            assertEquals("", cursor.getString(1))
        }
        // tab_group テーブルが作成されている
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='tab_group'").use { cursor ->
            assertTrue(cursor.moveToFirst())
        }
    }

    /** v2→v3: tab_group.isDefault 追加。既存グループが保持され isDefault が 0 になることを確認 */
    @Test
    fun migrate2To3() {
        helper.createDatabase(TEST_DB, 2).apply {
            execSQL(
                "INSERT INTO tab_group (groupId, name, sortOrder) VALUES ('g1', 'Group', 0)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            3,
            true,
            *TabDatabase.allMigrations(sessionStateDir()),
        )

        db.query("SELECT groupId, isDefault FROM tab_group WHERE groupId = 'g1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("g1", cursor.getString(0))
            assertEquals(0, cursor.getInt(1))
        }
    }

    /** v3→v4: sessionState をファイルへ移行しカラム削除。既存タブのデータが保持されることを確認 */
    @Test
    fun migrate3To4() {
        val sessionDir = sessionStateDir()

        helper.createDatabase(TEST_DB, 3).apply {
            execSQL(
                "INSERT INTO tab_state " +
                    "(tabId, url, sessionState, title, openerTabId, themeColor, sortOrder, isSelected, groupId) " +
                    "VALUES ('t1', 'https://example.com', 'session_data', 'Example', '', NULL, 0, 1, '')",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            4,
            true,
            *TabDatabase.allMigrations(sessionDir),
        )

        db.query("SELECT tabId, url, title FROM tab_state WHERE tabId = 't1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("t1", cursor.getString(0))
            assertEquals("https://example.com", cursor.getString(1))
            assertEquals("Example", cursor.getString(2))
        }
        // sessionState カラムが削除されている
        db.query("PRAGMA table_info(tab_state)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val columnNames = buildList {
                while (cursor.moveToNext()) {
                    add(cursor.getString(nameIndex))
                }
            }
            assertFalse(columnNames.contains("sessionState"))
        }
        // sessionState がファイルに移行されている
        val sessionFile = File(sessionDir, "t1")
        assertTrue(sessionFile.exists())
        assertEquals("session_data", sessionFile.readText())
    }

    /** v3→v4: 既にファイルが存在する場合は上書きしない */
    @Test
    fun migrate3To4_existingFileNotOverwritten() {
        val sessionDir = sessionStateDir()
        File(sessionDir, "t1").writeText("already_migrated")

        helper.createDatabase(TEST_DB, 3).apply {
            execSQL(
                "INSERT INTO tab_state " +
                    "(tabId, url, sessionState, title, openerTabId, themeColor, sortOrder, isSelected, groupId) " +
                    "VALUES ('t1', 'https://example.com', 'old_db_data', 'Example', '', NULL, 0, 1, '')",
            )
            close()
        }

        helper.runMigrationsAndValidate(
            TEST_DB,
            4,
            true,
            *TabDatabase.allMigrations(sessionDir),
        )

        // 既にファイルが存在する場合は上書きしない（アプリ側で既に移行済み）
        assertEquals("already_migrated", File(sessionDir, "t1").readText())
    }

    /** v1 から最新バージョンまで全マイグレーションを連続適用できることを確認 */
    @Test
    fun migrateAllFrom1() {
        val sessionDir = sessionStateDir()

        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                "INSERT INTO tab_state " +
                    "(tabId, url, sessionState, title, openerTabId, themeColor, sortOrder, isSelected) " +
                    "VALUES ('t1', 'https://example.com', 'state', 'Example', '', NULL, 0, 1)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            TabDatabase.SCHEMA_VERSION,
            true,
            *TabDatabase.allMigrations(sessionDir),
        )

        db.query("SELECT count(*) FROM tab_state").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        assertFalse(db.isReadOnly)
        // v1 から最新まで適用すると sessionState がファイルに移行されている
        assertEquals("state", File(sessionDir, "t1").readText())
    }

    private fun sessionStateDir(): File = File(tempFolder.root, "session_states").apply { mkdirs() }

    companion object {
        private const val TEST_DB = "migration-test-tab"
    }
}
