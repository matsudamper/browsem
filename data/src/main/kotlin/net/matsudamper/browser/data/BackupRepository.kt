package net.matsudamper.browser.data

import android.content.Context
import android.net.Uri
import java.io.File
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

    suspend fun exportToZip(outputUri: Uri): Unit = withContext(Dispatchers.IO) {
        val snapshotFile = File(context.cacheDir, "backup_tab_snapshot.db")
        snapshotFile.delete()
        try {
            // WAL を含めた整合性のあるスナップショットを別ファイルに書き出す。
            // VACUUM INTO は実行中に主データベースへ排他ロックを取るため、
            // 他のコルーチンの書き込みがあれば一時的にブロックされる点に注意。
            val db = TabDatabase.getInstance(context)
            val escapedPath = snapshotFile.absolutePath.replace("'", "''")
            db.openHelper.writableDatabase.execSQL("VACUUM INTO '$escapedPath'")

            val settingsFile = settingsFile()
            require(settingsFile.exists()) { "設定ファイルが見つかりません" }

            val outputStream = context.contentResolver.openOutputStream(outputUri, "w")
                ?: error("出力先を開けませんでした")
            ZipOutputStream(outputStream.buffered()).use { zos ->
                writeEntry(zos, SETTINGS_ENTRY_NAME, settingsFile)
                writeEntry(zos, TAB_DB_ENTRY_NAME, snapshotFile)
            }
        } finally {
            snapshotFile.delete()
        }
    }

    suspend fun importFromZip(inputUri: Uri): Unit = withContext(Dispatchers.IO) {
        val workDir = File(context.cacheDir, "backup_restore").apply {
            deleteRecursively()
            mkdirs()
        }
        // 置換先と同じファイルシステムにステージング用ファイルを置く。
        // 同 FS であれば renameTo がほぼ確実に成功し、close 後の置換が短時間で完了する。
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

            // ステージング成功後に DB を閉じて置換に入る。
            // タブ永続化が裏で tab.db を書き続けているため、置き換え前に
            // Room の接続を完全に閉じてインフライト書き込みを止める。
            TabDatabase.closeInstance()
            dbClosed = true

            // 古い WAL/SHM は新 DB と整合しないので削除する
            File(tabDbTarget.parentFile, "$TAB_DB_FILE_NAME-shm").delete()
            File(tabDbTarget.parentFile, "$TAB_DB_FILE_NAME-wal").delete()
            replaceWithStaging(settingsStaging, settingsTarget)
            replaceWithStaging(tabDbStaging, tabDbTarget)
        } catch (t: Throwable) {
            // ステージング段階の失敗ならファイルを掃除して通常動作に戻れる。
            // DB を閉じた後の失敗は app の状態が壊れているので、
            // ステージングだけ掃除して例外を再送出し、UI 側で再起動を促す。
            settingsStaging.delete()
            tabDbStaging.delete()
            throw t
        } finally {
            workDir.deleteRecursively()
            // 念のため: 正常終了時にもステージングが残らないようにする
            if (!dbClosed) {
                settingsStaging.delete()
                tabDbStaging.delete()
            }
        }
    }

    /** 同 FS 上でのファイル置換。rename が失敗した場合のみ copy にフォールバック */
    private fun replaceWithStaging(src: File, dst: File) {
        if (dst.exists()) dst.delete()
        if (!src.renameTo(dst)) {
            src.copyTo(dst, overwrite = true)
            src.delete()
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
