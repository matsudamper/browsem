package net.matsudamper.browser.data.history

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * BrowserDatabase のマイグレーションテスト。
 *
 * Room 公式手順に従い、エクスポート済みスキーマ JSON と [MigrationTestHelper] を使って
 * マイグレーション後のスキーマ検証とデータ保持を確認する。
 */
@RunWith(RobolectricTestRunner::class)
class BrowserDatabaseMigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        BrowserDatabase::class.java,
    )

    /** v1→v2: 検索高速化用インデックスの追加。既存履歴が保持されインデックスが作成されることを確認 */
    @Test
    fun migrate1To2() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                "INSERT INTO history (url, title, visitedAt) " +
                    "VALUES ('https://example.com', 'Example', 1000)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, *BrowserDatabase.ALL_MIGRATIONS)

        db.query("SELECT count(*) FROM history").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        // マイグレーションで追加したインデックスが存在する
        db.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND name='index_history_visitedAt'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
        }
        db.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND name='index_history_url_visitedAt'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
        }
    }

    companion object {
        private const val TEST_DB = "migration-test-history"
    }
}
