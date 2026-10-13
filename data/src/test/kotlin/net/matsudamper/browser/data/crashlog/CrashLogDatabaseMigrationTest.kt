package net.matsudamper.browser.data.crashlog

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CrashLogDatabaseMigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CrashLogDatabase::class.java,
    )

    @Test
    fun migrate1To2_assignsTypeAndStripsInfoPrefix() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                "INSERT INTO crash_log (occurredAt, title, body) VALUES (1000, 'java.lang.RuntimeException: boom', 'stack')",
            )
            execSQL(
                "INSERT INTO crash_log (occurredAt, title, body) VALUES (2000, 'INFO: 翻訳診断', 'stage=scan')",
            )
            close()
        }

        helper.runMigrationsAndValidate(
            TEST_DB,
            2,
            true,
            *CrashLogDatabase.ALL_MIGRATIONS,
        ).use { db ->
            db.query("SELECT title, type FROM crash_log ORDER BY occurredAt ASC").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("java.lang.RuntimeException: boom", cursor.getString(0))
                assertEquals(CrashLogRepository.TAG_CRASH, cursor.getString(1))
                assertTrue(cursor.moveToNext())
                assertEquals("翻訳診断", cursor.getString(0))
                assertEquals(CrashLogRepository.TAG_LOG, cursor.getString(1))
            }
        }
    }

    @Test
    fun migrate2To3_renamesTypeColumnToTag() {
        helper.createDatabase(TEST_DB, 2).apply {
            execSQL(
                "INSERT INTO crash_log (occurredAt, type, title, body) VALUES (1000, 'Crash', 'boom', 'stack')",
            )
            close()
        }

        helper.runMigrationsAndValidate(
            TEST_DB,
            3,
            true,
            *CrashLogDatabase.ALL_MIGRATIONS,
        ).use { db ->
            db.query("SELECT tag, title FROM crash_log").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(CrashLogRepository.TAG_CRASH, cursor.getString(0))
                assertEquals("boom", cursor.getString(1))
            }
        }
    }

    private companion object {
        const val TEST_DB = "crash-log-migration-test"
    }
}
