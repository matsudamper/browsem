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

        helper.runMigrationsAndValidate(TEST_DB, 2, true, *DownloadDatabase.ALL_MIGRATIONS).use { db ->
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
    }

    /**
     * v2→v4: currentWorkerId カラム追加（既存レコードは workerId で埋める）と
     * currentWorkerId へのインデックス追加。既存レコードの保持とバックフィルを確認する。
     */
    @Test
    fun migrate2To4() {
        helper.createDatabase(TEST_DB, 2).apply {
            execSQL(
                "INSERT INTO download " +
                    "(workerId, url, fileName, fileUri, status, progress, totalRead, contentLength, " +
                    "enqueuedAt, referrerUrl, partialFileUri) " +
                    "VALUES ('w2', 'https://example.com/b.zip', 'b.zip', NULL, 'RUNNING', 50, 100, 200, " +
                    "1000, '', NULL)",
            )
            close()
        }

        helper.runMigrationsAndValidate(TEST_DB, 4, true, *DownloadDatabase.ALL_MIGRATIONS).use { db ->
            db.query(
                "SELECT workerId, currentWorkerId FROM download WHERE workerId = 'w2'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("w2", cursor.getString(0))
                // currentWorkerId は workerId と同値でバックフィルされる
                assertEquals("w2", cursor.getString(1))
            }
            // currentWorkerId にインデックスが作成されている
            db.query(
                "SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_download_currentWorkerId'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
            }
        }
    }

    companion object {
        private const val TEST_DB = "migration-test-download"
    }
}
