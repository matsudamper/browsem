package net.matsudamper.browser.data.crashlog

import android.content.Context
import kotlinx.coroutines.flow.Flow

class CrashLogRepository(context: Context) {
    private val db = CrashLogDatabase.getInstance(context)
    private val dao = db.crashLogDao()

    fun observeAll(): Flow<List<CrashLogEntity>> = dao.observeAll()

    suspend fun getById(id: Long): CrashLogEntity? = dao.getById(id)

    suspend fun deleteAll() = dao.deleteAll()

    fun saveCrashSync(thread: Thread, throwable: Throwable) {
        val entity = CrashLogEntity(
            occurredAt = System.currentTimeMillis(),
            title = CrashLogFormatter.extractTitle(throwable),
            body = CrashLogFormatter.formatBody(thread, throwable),
        )
        dao.insert(entity)
    }
}
