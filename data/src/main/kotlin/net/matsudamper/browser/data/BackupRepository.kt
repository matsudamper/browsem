package net.matsudamper.browser.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.matsudamper.browser.data.tab.TabDatabase

/**
 * 設定 (Proto DataStore) とタブデータ (tab.db) のみを zip ファイルで
 * エクスポート/インポートするリポジトリ。
 * 履歴・ダウンロード記録・GeckoView プロファイル (Cookie・セッション) は対象外。
 *
 * インポートは DataStore と Room のキャッシュを無視して直接ファイルを置き換えるため、
 * 呼び出し側はインポート成功後に必ずプロセスを終了させ、再起動を促すこと。
 */
class BackupRepository(private val context: Context) {

    /** Room を閉じた後に失敗したことを示す。受け取った側は強制再起動 UX に分岐すること */
    class RestartRequiredException(cause: Throwable) :
        IOException(cause.message ?: cause::class.simpleName, cause)

    suspend fun exportToZip(outputUri: Uri): Unit = withContext(Dispatchers.IO) {
        val snapshotFile = File(context.cacheDir, "backup_tab_snapshot.db")
        val defaultSettingsFile = File(context.cacheDir, "backup_default_settings.pb")
        snapshotFile.delete()
        defaultSettingsFile.delete()
        try {
            // WAL を含めた整合性のあるスナップショットを別ファイルに書き出す。
            // VACUUM INTO は実行中に主データベースへ排他ロックを取るため、
            // 他のコルーチンの書き込みがあれば一時的にブロックされる点に注意。
            val db = TabDatabase.getInstance(context)
            val escapedPath = snapshotFile.absolutePath.replace("'", "''")
            db.openHelper.writableDatabase.execSQL("VACUUM INTO '$escapedPath'")

            // 新規インストール直後など、まだ DataStore が一度も書き込みを
            // 行っていない場合は本体ファイルが存在しない。その場合は
            // デフォルト設定のシリアライズ結果を書き出してエントリに含める。
            val settingsForExport = settingsFile().takeIf { it.exists() } ?: run {
                defaultSettingsFile.outputStream().use { out ->
                    BrowserSettings.getDefaultInstance().writeTo(out)
                }
                defaultSettingsFile
            }

            val outputStream = context.contentResolver.openOutputStream(outputUri, "w")
                ?: error("出力先を開けませんでした")
            ZipOutputStream(outputStream.buffered()).use { zos ->
                writeEntry(zos, SETTINGS_ENTRY_NAME, settingsForExport)
                writeEntry(zos, TAB_DB_ENTRY_NAME, snapshotFile)
            }
        } finally {
            snapshotFile.delete()
            defaultSettingsFile.delete()
        }
    }

    suspend fun importFromZip(inputUri: Uri): Unit = withContext(Dispatchers.IO) {
        val workDir = File(context.cacheDir, "backup_restore").apply {
            deleteRecursively()
            mkdirs()
        }
        // 置換先と同じファイルシステムにステージング用ファイルを置く。
        // 同 FS であれば atomic な rename で置換でき、close 後の置換が短時間で完了する。
        val settingsTarget = settingsFile().apply { parentFile?.mkdirs() }
        val tabDbTarget = context.getDatabasePath(TAB_DB_FILE_NAME).apply {
            parentFile?.mkdirs()
        }
        val settingsStaging = File(settingsTarget.parentFile, "$SETTINGS_FILE_NAME.import")
        val tabDbStaging = File(tabDbTarget.parentFile, "$TAB_DB_FILE_NAME.import")
        var dbClosed = false
        try {
            val extracted = mutableMapOf<String, File>()
            val inputStream = context.contentResolver.openInputStream(inputUri)
                ?: error("バックアップファイルを開けませんでした")
            ZipInputStream(inputStream.buffered()).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val name = entry.name
                    if (name in ALLOWED_ENTRY_NAMES) {
                        val out = File(workDir, name)
                        out.outputStream().buffered().use { dst -> zis.copyTo(dst) }
                        extracted[name] = out
                    }
                    zis.closeEntry()
                }
            }

            val settingsExtracted = extracted[SETTINGS_ENTRY_NAME]
                ?: error("バックアップに設定ファイルが含まれていません")
            val tabDbExtracted = extracted[TAB_DB_ENTRY_NAME]
                ?: error("バックアップにタブデータが含まれていません")

            // リスクのあるコピー (容量不足・I/O エラー) を先に済ませる。
            // この時点では Room は生きているので失敗しても app は通常動作に戻れる。
            settingsExtracted.copyTo(settingsStaging, overwrite = true)
            tabDbExtracted.copyTo(tabDbStaging, overwrite = true)

            // 新しいアプリ版で作成したバックアップを古いアプリ版で復元すると Room が起動不能に
            // なるため、close 前に staging の tab.db を読み取って互換性を確認する。
            val backupSchemaVersion = readSchemaVersion(tabDbStaging)
            if (backupSchemaVersion > TabDatabase.SCHEMA_VERSION) {
                error(
                    "バックアップは新しいアプリ版で作成されています " +
                        "(スキーマ $backupSchemaVersion > 現バージョン ${TabDatabase.SCHEMA_VERSION})。" +
                        "アプリを更新してから復元してください",
                )
            }

            // ステージング成功後に DB を閉じて置換に入る。
            // タブ永続化が裏で tab.db を書き続けているため、置き換え前に
            // Room の接続を完全に閉じてインフライト書き込みを止める。
            TabDatabase.closeInstance()
            dbClosed = true

            // close 後の失敗は強制再起動が必要 (Repository が閉じた DB 参照を持つため)
            try {
                // 古い WAL/SHM は新 DB と整合しないので削除する
                File(tabDbTarget.parentFile, "$TAB_DB_FILE_NAME-shm").delete()
                File(tabDbTarget.parentFile, "$TAB_DB_FILE_NAME-wal").delete()
                replaceWithStaging(settingsStaging, settingsTarget)
                replaceWithStaging(tabDbStaging, tabDbTarget)
            } catch (t: Throwable) {
                throw RestartRequiredException(t)
            }
        } catch (t: Throwable) {
            // close 前の失敗ならステージングを掃除して通常動作に戻る。
            // close 後の失敗 (RestartRequiredException) はそのまま再送出。
            if (!dbClosed) {
                settingsStaging.delete()
                tabDbStaging.delete()
            }
            throw t
        } finally {
            workDir.deleteRecursively()
        }
    }

    /**
     * 同 FS 上で staging ファイルを dst の位置に置換する。
     * `ATOMIC_MOVE` を明示することで、対応する FS (Android 内部ストレージの ext4
     * 等) では rename(2) による atomic な置換を保証する。
     * 仮に非対応 FS だった場合は `AtomicMoveNotSupportedException` を捕まえて
     * REPLACE_EXISTING のみで再試行することで、復元動作自体が止まらないようにする。
     */
    private fun replaceWithStaging(src: File, dst: File) {
        try {
            Files.move(
                src.toPath(),
                dst.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** SQLite の PRAGMA user_version (= Room の schema version) を読み取る */
    private fun readSchemaVersion(file: File): Int {
        return SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { db ->
            db.rawQuery("PRAGMA user_version", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
        }
    }

    private fun settingsFile(): File =
        File(context.filesDir, "$DATASTORE_DIR_NAME/$SETTINGS_FILE_NAME")

    private fun writeEntry(zos: ZipOutputStream, name: String, source: File) {
        zos.putNextEntry(ZipEntry(name))
        source.inputStream().buffered().use { it.copyTo(zos) }
        zos.closeEntry()
    }

    companion object {
        const val MIME_TYPE: String = "application/zip"
        const val FILE_EXTENSION: String = "zip"

        private const val SETTINGS_FILE_NAME = "browser_settings.pb"
        private const val TAB_DB_FILE_NAME = "tab.db"
        private const val DATASTORE_DIR_NAME = "datastore"
        private const val SETTINGS_ENTRY_NAME = SETTINGS_FILE_NAME
        private const val TAB_DB_ENTRY_NAME = TAB_DB_FILE_NAME
        private val ALLOWED_ENTRY_NAMES = setOf(SETTINGS_ENTRY_NAME, TAB_DB_ENTRY_NAME)
    }
}
