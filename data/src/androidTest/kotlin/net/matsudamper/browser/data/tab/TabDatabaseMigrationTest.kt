package net.matsudamper.browser.data.tab

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TabDatabase のマイグレーションテスト。
 *
 * Room 公式手順 (https://developer.android.com/training/data-storage/room/migrating-db-versions#test)
 * に従い、エクスポート済みスキーマ JSON と [MigrationTestHelper] を使って
 * 各バージョンのスキーマを作成し、マイグレーション後にスキーマ検証とデータ保持を確認する。
 */
@RunWith(AndroidJUnit4::class)
class TabDatabaseMigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        TabDatabase::class.java,
    )

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

        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, *TabDatabase.ALL_MIGRATIONS)

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

        val db = helper.runMigrationsAndValidate(TEST_DB, 3, true, *TabDatabase.ALL_MIGRATIONS)

        db.query("SELECT groupId, isDefault FROM tab_group WHERE groupId = 'g1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("g1", cursor.getString(0))
            assertEquals(0, cursor.getInt(1))
        }
    }

    /** v1 から最新バージョンまで全マイグレーションを連続適用できることを確認 */
    @Test
    fun migrateAllFrom1() {
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
            *TabDatabase.ALL_MIGRATIONS,
        )

        db.query("SELECT count(*) FROM tab_state").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        assertFalse(db.isReadOnly)
    }

    companion object {
        private const val TEST_DB = "migration-test-tab"
    }
}
