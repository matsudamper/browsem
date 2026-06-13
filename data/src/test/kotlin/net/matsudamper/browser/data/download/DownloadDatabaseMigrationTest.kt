package net.matsudamper.browser.data.download

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * DownloadDatabase のマイグレーションテスト。
 *
 * Room 公式手順に従い、エクスポート済みスキーマ JSON と [MigrationTestHelper] を使って
 * マイグレーション後のスキーマ検証とデータ保持を確認する。
 */
@RunWith(RobolectricTestRunner::class)
class DownloadDatabaseMigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DownloadDatabase::class.java,
    )

    /** v1→v2: referrerUrl と partialFileUri 追加。既存レコードが保持されることを確認 */
    @Test
    fun migrate1To2() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                "INSERT INTO download " +
                    "(workerId, url, fileName, fileUri, status, progress, totalRead, contentLength, enqueuedAt) " +
                    "VALUES ('w1', 'https://example.com/a.zip', 'a.zip', NULL, 'RUNNING', 50, 100, 200, 1000)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, *DownloadDatabase.ALL_MIGRATIONS)

        db.query(
            "SELECT workerId, referrerUrl, partialFileUri FROM download WHERE workerId = 'w1'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("w1", cursor.getString(0))
            // referrerUrl は NOT NULL DEFAULT '' のため空文字
            assertEquals("", cursor.getString(1))
            // partialFileUri は nullable のため NULL
            assertTrue(cursor.isNull(2))
            assertNull(cursor.getString(2))
        }
    }

    companion object {
        private const val TEST_DB = "migration-test-download"
    }
}
