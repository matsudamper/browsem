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
            type = TYPE_CRASH,
            title = CrashLogFormatter.extractTitle(throwable),
            body = CrashLogFormatter.formatBody(thread, throwable),
        )
    }

    fun saveInfoSync(title: String, body: String) {
        saveSync(
            type = TYPE_LOG,
            title = title,
            body = body,
        )
    }

    fun saveSync(type: String, title: String, body: String) {
        dao.insert(
            CrashLogEntity(
                occurredAt = System.currentTimeMillis(),
                type = type,
                title = title,
                body = body,
            ),
        )
    }

    companion object {
        const val TYPE_CRASH = "Crash"
        const val TYPE_LOG = "Log"
    }
}
