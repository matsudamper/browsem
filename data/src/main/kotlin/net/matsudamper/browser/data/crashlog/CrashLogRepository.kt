package net.matsudamper.browser.data.crashlog

import android.content.Context
import kotlinx.coroutines.flow.Flow

class CrashLogRepository(context: Context) {
    private val db = CrashLogDatabase.getInstance(context)
    private val dao = db.crashLogDao()

    fun observeAllSummaries(): Flow<List<CrashLogListItem>> = dao.observeAllSummaries()

    suspend fun getById(id: Long): CrashLogEntity? = dao.getById(id)

    suspend fun deleteAll() = dao.deleteAll()

    fun saveCrashSync(thread: Thread, throwable: Throwable) {
        saveSync(
            tag = TAG_CRASH,
            title = CrashLogFormatter.extractTitle(throwable),
            body = CrashLogFormatter.formatBody(thread, throwable),
        )
    }

    fun saveInfoSync(title: String, body: String) {
        saveSync(
            tag = TAG_LOG,
            title = title,
            body = body,
        )
    }

    fun saveSync(tag: String, title: String, body: String) {
        val entry = CrashLogEntity(
            occurredAt = System.currentTimeMillis(),
            tag = tag,
            title = title,
            body = body,
        )
        if (tag.startsWith("MediaPlayback/")) {
            db.runInTransaction {
                dao.insert(entry)
                dao.deleteOldMediaDiagnostics(MAX_MEDIA_DIAGNOSTICS)
            }
        } else {
            dao.insert(entry)
        }
    }

    companion object {
        const val TAG_CRASH = "Crash"
        const val TAG_LOG = "Log"
        const val MAX_MEDIA_DIAGNOSTICS = 100
    }
}
