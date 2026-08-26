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
import net.matsudamper.browser.data.address.AddressDatabase
import net.matsudamper.browser.data.tab.TabDatabase

/**
 * 設定 (Proto DataStore)・タブデータ (tab.db)・住所 (address.db)・GeckoView プロファイル
 * (Cookie・ログイン情報・履歴・サイト権限・サイト別設定) を zip ファイルで
 * エクスポート/インポートするリポジトリ。
 * 履歴 (browser.db 側)・ダウンロード記録は対象外。
 *
 * インポートは DataStore・Room のキャッシュおよび GeckoView 実行中の
 * プロファイル参照を無視して直接ファイルを置き換えるため、
 * 呼び出し側はインポート成功後に必ずプロセスを終了させ、再起動を促すこと。
 */
class BackupRepository(private val context: Context) {

    /** Room を閉じた後／GeckoView プロファイルを置換した後の失敗を示す。受け取った側は強制再起動 UX に分岐すること */
    class RestartRequiredException(cause: Throwable) :
        IOException(cause.message ?: cause::class.simpleName, cause)

    suspend fun exportToZip(
        outputUri: Uri,
        onProgress: (BackupProgress) -> Unit = {},
    ): Unit = withContext(Dispatchers.IO) {
        val progress = ProgressReporter(onProgress)
        val snapshotFile = File(context.cacheDir, "backup_tab_snapshot.db")
        val addressSnapshotFile = File(context.cacheDir, "backup_address_snapshot.db")
        val defaultSettingsFile = File(context.cacheDir, "backup_default_settings.pb")
        snapshotFile.delete()
        addressSnapshotFile.delete()
        defaultSettingsFile.delete()
        try {
            // WAL を含めた整合性のあるスナップショットを別ファイルに書き出す。
            // VACUUM INTO は実行中に主データベースへ排他ロックを取るため、
            // 他のコルーチンの書き込みがあれば一時的にブロックされる点に注意。
            progress.report("タブデータのスナップショットを作成中…", 0f)
            val db = TabDatabase.getInstance(context)
            val escapedPath = snapshotFile.absolutePath.replace("'", "''")
            db.openHelper.writableDatabase.execSQL("VACUUM INTO '$escapedPath'")
            progress.report("タブデータのスナップショットを作成中…", 0.15f)

            progress.report("住所データのスナップショットを作成中…", 0.18f)
            val addressDb = AddressDatabase.getInstance(context)
            val escapedAddressPath = addressSnapshotFile.absolutePath.replace("'", "''")
            addressDb.openHelper.writableDatabase.execSQL("VACUUM INTO '$escapedAddressPath'")
            progress.report("住所データのスナップショットを作成中…", 0.22f)

            // 新規インストール直後など、まだ DataStore が一度も書き込みを
            // 行っていない場合は本体ファイルが存在しない。その場合は
            // デフォルト設定のシリアライズ結果を書き出してエントリに含める。
            progress.report("設定を読み込み中…", 0.20f)
            val settingsForExport = settingsFile().takeIf { it.exists() } ?: run {
                defaultSettingsFile.outputStream().use { out ->
                    BrowserSettings.getDefaultInstance().writeTo(out)
                }
                defaultSettingsFile
            }

            val mozillaProfileFiles = listMozillaProfileFiles()
            val outputStream = context.contentResolver.openOutputStream(outputUri, "w")
                ?: error("出力先を開けませんでした")
            ZipOutputStream(outputStream.buffered()).use { zos ->
                progress.report("設定を書き出し中…", 0.25f)
                writeEntry(zos, SETTINGS_ENTRY_NAME, settingsForExport)
                progress.report("タブデータを書き出し中…", 0.35f)
                writeEntry(zos, TAB_DB_ENTRY_NAME, snapshotFile)
                progress.report("住所データを書き出し中…", 0.40f)
                writeEntry(zos, ADDRESS_DB_ENTRY_NAME, addressSnapshotFile)
                writeMozillaProfileEntries(
                    zos = zos,
                    files = mozillaProfileFiles,
                    onFileWritten = { index ->
                        if (
                            mozillaProfileFiles.isEmpty() ||
                            index % 10 == 0 ||
                            index == mozillaProfileFiles.lastIndex
                        ) {
                            progress.reportRange(
                                message = "プロファイルを書き出し中…",
                                index = index,
                                total = mozillaProfileFiles.size,
                                rangeStart = 0.35f,
                                rangeEnd = 1f,
                            )
                        }
                    },
                )
            }
            progress.report("書き出しを完了しています…", 1f)
        } finally {
            snapshotFile.delete()
            addressSnapshotFile.delete()
            defaultSettingsFile.delete()
        }
    }

    suspend fun importFromZip(
        inputUri: Uri,
        onProgress: (BackupProgress) -> Unit = {},
    ): Unit = withContext(Dispatchers.IO) {
        val progress = ProgressReporter(onProgress)
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
        val addressDbTarget = context.getDatabasePath(ADDRESS_DB_FILE_NAME).apply {
            parentFile?.mkdirs()
        }
        val settingsStaging = File(settingsTarget.parentFile, "$SETTINGS_FILE_NAME.import")
        val tabDbStaging = File(tabDbTarget.parentFile, "$TAB_DB_FILE_NAME.import")
        val addressDbStaging = File(addressDbTarget.parentFile, "$ADDRESS_DB_FILE_NAME.import").apply {
            // 前回の失敗で残った staging が、住所なし ZIP の復元で誤って使われないようにする
            delete()
        }
        // GeckoView プロファイル staging はターゲットと同じ filesDir 直下に置き、
        // 同 FS 上での rename による atomic swap を可能にする。
        val mozillaTarget = mozillaDir()
        val mozillaStaging = File(context.filesDir, "$MOZILLA_DIR_NAME.import").apply {
            deleteRecursively()
        }
        var dbClosed = false
        var mozillaReplaced = false
        try {
            val extracted = mutableMapOf<String, File>()
            var hasMozillaPayload = false
            val mozillaStagingRoot = mozillaStaging.canonicalFile
            progress.report("バックアップを読み込み中…", 0f)
            val importableEntryCount = countImportableZipEntries(inputUri)
            progress.report("バックアップを展開中…", 0.05f)
            val inputStream = context.contentResolver.openInputStream(inputUri)
                ?: error("バックアップファイルを開けませんでした")
            var extractedEntryCount = 0
            ZipInputStream(inputStream.buffered()).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val name = entry.name
                    try {
                        when {
                            entry.isDirectory -> Unit
                            name in ALLOWED_ROOT_ENTRY_NAMES -> {
                                val out = File(workDir, name)
                                out.outputStream().buffered().use { dst -> zis.copyTo(dst) }
                                extracted[name] = out
                                extractedEntryCount++
                                progress.reportRange(
                                    message = "バックアップを展開中…",
                                    index = extractedEntryCount - 1,
                                    total = importableEntryCount,
                                    rangeStart = 0.05f,
                                    rangeEnd = 0.55f,
                                )
                            }
                            name.startsWith("$MOZILLA_DIR_NAME/") -> {
                                val relative = name.substring(MOZILLA_DIR_NAME.length + 1)
                                if (relative.isEmpty()) {
                                    // mozilla/ 自体のディレクトリエントリ。中身が無くてもプロファイル置換は実施する
                                    if (!mozillaStagingRoot.exists()) mozillaStagingRoot.mkdirs()
                                    hasMozillaPayload = true
                                } else {
                                    // ZIP slip 対策: 解決後のパスが staging ルート配下であることを保証する
                                    val out = File(mozillaStagingRoot, relative).canonicalFile
                                    if (!out.path.startsWith(mozillaStagingRoot.path + File.separator)) {
                                        error("不正な zip エントリパスです: $name")
                                    }
                                    out.parentFile?.mkdirs()
                                    out.outputStream().buffered().use { dst -> zis.copyTo(dst) }
                                    hasMozillaPayload = true
                                    extractedEntryCount++
                                    progress.reportRange(
                                        message = "バックアップを展開中…",
                                        index = extractedEntryCount - 1,
                                        total = importableEntryCount,
                                        rangeStart = 0.05f,
                                        rangeEnd = 0.55f,
                                    )
                                }
                            }
                            else -> Unit
                        }
                    } finally {
                        zis.closeEntry()
                    }
                }
            }

            val settingsExtracted = extracted[SETTINGS_ENTRY_NAME]
                ?: error("バックアップに設定ファイルが含まれていません")
            val tabDbExtracted = extracted[TAB_DB_ENTRY_NAME]
                ?: error("バックアップにタブデータが含まれていません")
            val addressDbExtracted = extracted[ADDRESS_DB_ENTRY_NAME]

            // リスクのあるコピー (容量不足・I/O エラー) を先に済ませる。
            // この時点では Room は生きているので失敗しても app は通常動作に戻れる。
            progress.report("ファイルを準備中…", 0.60f)
            settingsExtracted.copyTo(settingsStaging, overwrite = true)
            tabDbExtracted.copyTo(tabDbStaging, overwrite = true)
            addressDbExtracted?.copyTo(addressDbStaging, overwrite = true)

            // 新しいアプリ版で作成したバックアップを古いアプリ版で復元すると Room が起動不能に
            // なるため、close 前に staging の tab.db を読み取って互換性を確認する。
            // user_version=0 (未初期化や外部由来) や対応マイグレーションがないバージョンも、
            // 復元後の起動で Room が「missing migration」で落ちるため弾く。
            progress.report("バックアップを検証中…", 0.65f)
            val backupSchemaVersion = readSchemaVersion(tabDbStaging)
            if (backupSchemaVersion !in 1..TabDatabase.SCHEMA_VERSION) {
                error(
                    "バックアップの tab.db スキーマバージョン ($backupSchemaVersion) はサポート範囲外です " +
                        "(対応範囲: 1〜${TabDatabase.SCHEMA_VERSION})。" +
                        "破損したバックアップか、新しいアプリ版で作成された可能性があります",
                )
            }
            if (addressDbExtracted != null) {
                val addressSchemaVersion = readSchemaVersion(addressDbStaging)
                if (addressSchemaVersion !in 1..AddressDatabase.SCHEMA_VERSION) {
                    error(
                        "バックアップの address.db スキーマバージョン ($addressSchemaVersion) はサポート範囲外です " +
                            "(対応範囲: 1〜${AddressDatabase.SCHEMA_VERSION})。" +
                            "破損したバックアップか、新しいアプリ版で作成された可能性があります",
                    )
                }
            }

            // ステージング成功後に DB を閉じて置換に入る。
            // タブ永続化が裏で tab.db を書き続けているため、置き換え前に
            // Room の接続を完全に閉じてインフライト書き込みを止める。
            progress.report("データベースを閉じています…", 0.70f)
            TabDatabase.closeInstance()
            AddressDatabase.closeInstance()
            dbClosed = true

            // close 後の失敗は強制再起動が必要 (Repository が閉じた DB 参照を持つため)
            try {
                // 古い WAL/SHM は新 DB と整合しないので削除する
                File(tabDbTarget.parentFile, "$TAB_DB_FILE_NAME-shm").delete()
                File(tabDbTarget.parentFile, "$TAB_DB_FILE_NAME-wal").delete()
                File(addressDbTarget.parentFile, "$ADDRESS_DB_FILE_NAME-shm").delete()
                File(addressDbTarget.parentFile, "$ADDRESS_DB_FILE_NAME-wal").delete()
                progress.report("設定とタブデータを置き換え中…", 0.85f)
                replaceWithStaging(settingsStaging, settingsTarget)
                replaceWithStaging(tabDbStaging, tabDbTarget)
                if (addressDbExtracted != null) {
                    replaceWithStaging(addressDbStaging, addressDbTarget)
                }
                if (hasMozillaPayload) {
                    progress.report("プロファイルを置き換え中…", 0.95f)
                    swapMozillaProfile(mozillaStaging, mozillaTarget)
                    mozillaReplaced = true
                }
                progress.report("復元を完了しています…", 1f)
            } catch (t: Throwable) {
                throw RestartRequiredException(t)
            }
        } catch (t: Throwable) {
            // close 前の失敗ならステージングを掃除して通常動作に戻る。
            // close 後の失敗 (RestartRequiredException) はそのまま再送出。
            if (!dbClosed) {
                settingsStaging.delete()
                tabDbStaging.delete()
                addressDbStaging.delete()
                mozillaStaging.deleteRecursively()
            } else if (!mozillaReplaced) {
                // DB は閉じた／置換済みだが mozilla 置換前に失敗したケース。
                // staging のディレクトリだけ片付ける (mozillaTarget はまだ無傷)
                mozillaStaging.deleteRecursively()
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

    /**
     * GeckoView プロファイルディレクトリを staging で置換する。
     * Files.move は空でないディレクトリへの REPLACE_EXISTING を実装によって弾くため、
     * 「old へ退避 → staging を移動 → old を破棄」のシーケンスで安全に入れ替える。
     * 途中失敗時は old を本来の位置に戻して可能な限り元の状態に復帰させる。
     */
    private fun swapMozillaProfile(staging: File, target: File) {
        val backup = File(target.parentFile, "$MOZILLA_DIR_NAME.old").apply {
            // 前回失敗時に残ったゴミがあれば先に消す
            deleteRecursively()
        }
        val targetExisted = target.exists()
        if (targetExisted) {
            // rename(2) で同 FS 内なら atomic、空でないディレクトリでも問題なし
            if (!target.renameTo(backup)) {
                error("既存の GeckoView プロファイルを退避できませんでした")
            }
        }
        val moved = try {
            staging.renameTo(target)
        } catch (t: Throwable) {
            // 退避を戻して復元前の状態に近づける
            if (targetExisted) backup.renameTo(target)
            throw t
        }
        if (!moved) {
            if (targetExisted) backup.renameTo(target)
            error("GeckoView プロファイルの差し替えに失敗しました")
        }
        backup.deleteRecursively()
    }

    /**
     * GeckoView プロファイル (`files/mozilla/`) 配下のバックアップ対象ファイルを列挙する。
     */
    private fun listMozillaProfileFiles(): List<File> {
        val root = mozillaDir().takeIf { it.exists() && it.isDirectory } ?: return emptyList()
        val rootPath = root.absolutePath
        return root.walkTopDown()
            .onEnter { dir -> !isMozillaPathExcluded(rootPath, dir) }
            .filter { it.isFile && !isMozillaPathExcluded(rootPath, it) }
            .toList()
    }

    /**
     * GeckoView プロファイル (`files/mozilla/`) 配下のファイルを zip に追加する。
     * exclude (ブラックリスト) 方式を採用している:
     *   - Cookie・ログイン・履歴・権限・サイト別設定など、Gecko 側に追加される
     *     ユーザーデータ系ファイルを取りこぼさないため
     *   - キャッシュ系ディレクトリ名は GeckoView/Gecko の歴史的に安定しており、
     *     ブラックリストの方が将来の Gecko 更新に対して頑健
     * パスはセグメント単位で比較し、ディレクトリ名一致は配下を丸ごと除外する。
     */
    private fun writeMozillaProfileEntries(
        zos: ZipOutputStream,
        files: List<File>,
        onFileWritten: (index: Int) -> Unit,
    ) {
        if (files.isEmpty()) return
        val root = mozillaDir()
        val rootPath = root.absolutePath
        files.forEachIndexed { index, file ->
            val relative = file.absolutePath.substring(rootPath.length + 1)
                .replace(File.separatorChar, '/')
            writeEntry(zos, "$MOZILLA_DIR_NAME/$relative", file)
            onFileWritten(index)
        }
    }

    /** インポート対象の zip エントリ数を数える (展開前の進捗算出用) */
    private fun countImportableZipEntries(inputUri: Uri): Int {
        val inputStream = context.contentResolver.openInputStream(inputUri)
            ?: error("バックアップファイルを開けませんでした")
        return ZipInputStream(inputStream.buffered()).use { zis ->
            var count = 0
            while (true) {
                val entry = zis.nextEntry ?: break
                try {
                    if (!entry.isDirectory && isImportableZipEntry(entry.name)) {
                        count++
                    }
                } finally {
                    zis.closeEntry()
                }
            }
            count
        }
    }

    private fun isImportableZipEntry(name: String): Boolean {
        if (name in ALLOWED_ROOT_ENTRY_NAMES) return true
        if (!name.startsWith("$MOZILLA_DIR_NAME/")) return false
        val relative = name.substring(MOZILLA_DIR_NAME.length + 1)
        return relative.isNotEmpty()
    }

    private class ProgressReporter(
        private val onProgress: (BackupProgress) -> Unit,
    ) {
        fun report(message: String, fraction: Float? = null) {
            onProgress(
                BackupProgress(
                    message = message,
                    fraction = fraction?.coerceIn(0f, 1f),
                ),
            )
        }

        fun reportRange(
            message: String,
            index: Int,
            total: Int,
            rangeStart: Float,
            rangeEnd: Float,
        ) {
            if (total <= 0) {
                report(message, rangeEnd)
                return
            }
            val fraction = rangeStart + (rangeEnd - rangeStart) * (index + 1) / total
            val detail = if (total > 1) "$message (${index + 1}/$total)" else message
            report(detail, fraction)
        }
    }

    private fun isMozillaPathExcluded(rootPath: String, file: File): Boolean {
        val absolute = file.absolutePath
        if (absolute == rootPath) return false
        val relative = absolute.substring(rootPath.length + 1)
        val segments = relative.split(File.separatorChar)
        val name = segments.last().lowercase()
        // ディレクトリ名がブラックリスト一致 (どのセグメントでも)
        if (segments.any { it.lowercase() in MOZILLA_EXCLUDED_DIRS }) return true
        // ファイル名が完全一致
        if (name in MOZILLA_EXCLUDED_FILE_NAMES) return true
        // 拡張子ベースの除外
        return MOZILLA_EXCLUDED_SUFFIXES.any { name.endsWith(it) }
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

    private fun mozillaDir(): File = File(context.filesDir, MOZILLA_DIR_NAME)

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
        private const val ADDRESS_DB_FILE_NAME = "address.db"
        private const val DATASTORE_DIR_NAME = "datastore"
        private const val MOZILLA_DIR_NAME = "mozilla"
        private const val SETTINGS_ENTRY_NAME = SETTINGS_FILE_NAME
        private const val TAB_DB_ENTRY_NAME = TAB_DB_FILE_NAME
        private const val ADDRESS_DB_ENTRY_NAME = ADDRESS_DB_FILE_NAME
        private val ALLOWED_ROOT_ENTRY_NAMES = setOf(
            SETTINGS_ENTRY_NAME,
            TAB_DB_ENTRY_NAME,
            ADDRESS_DB_ENTRY_NAME,
        )

        // GeckoView プロファイルから除外するディレクトリ名 (小文字, セグメント完全一致)。
        // キャッシュ・クラッシュレポート・SafeBrowsing 等は復元先で再生成されるため不要。
        private val MOZILLA_EXCLUDED_DIRS = setOf(
            "cache2",
            "startupcache",
            "offlinecache",
            "thumbnails",
            "crashes",
            "minidumps",
            "safebrowsing",
            "datareporting",
            "shader-cache",
            "shadercache",
            "saved-telemetry-pings",
            "security_state",
        )

        // ファイル名完全一致の除外。プロファイルロックや実行時ログを含めると
        // 復元後に Gecko が起動拒否したり古いログを取り込んだりするため除外する。
        private val MOZILLA_EXCLUDED_FILE_NAMES = setOf(
            "lock",
            "parent.lock",
            ".parentlock",
            "compatibility.ini",
        )

        // 拡張子ベースの除外。ログ系のみ。
        private val MOZILLA_EXCLUDED_SUFFIXES = listOf(".log")
    }
}
