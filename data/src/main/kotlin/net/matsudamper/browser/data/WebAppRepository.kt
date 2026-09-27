package net.matsudamper.browser.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.matsudamper.browser.data.tab.TabDatabase
import net.matsudamper.browser.data.tab.WebAppEntity

class WebAppRepository(context: Context) {
    private val dao = TabDatabase.getInstance(context).webAppDao()

    // GeckoView の sessionState は CursorWindow 上限を超え得るため、タブと同様に DB ではなくファイルへ保存する
    private val sessionStateDir = File(context.filesDir, "web_app_session_states")

    // 登録の確認からファイル書き込みまでの間に削除が割り込むと、削除済みのファイルを作り直して孤立させるため排他する
    private val sessionStateMutex = Mutex()

    fun observeWebApps(): Flow<List<WebAppData>> {
        return dao.observeWebApps().map { entities -> entities.map { it.toWebAppData() } }
    }

    suspend fun getWebApp(webAppId: WebAppId): WebAppData? {
        return dao.getWebApp(webAppId.value)?.toWebAppData()
    }

    suspend fun addWebApp(profileId: ProfileId, startUrl: String, title: String): WebAppId {
        val webAppId = WebAppId.generate()
        dao.upsertWebApp(
            WebAppEntity(
                webAppId = webAppId.value,
                profileId = profileId.value,
                startUrl = startUrl,
                title = title,
                createdAt = System.currentTimeMillis(),
            ),
        )
        return webAppId
    }

    suspend fun deleteWebApp(webAppId: WebAppId) {
        sessionStateMutex.withLock {
            dao.deleteWebApp(webAppId.value)
            withContext(Dispatchers.IO) {
                sessionStateFile(webAppId).delete()
            }
        }
    }

    /** [keepWebAppIds] に含まれないウェブアプリを削除する */
    suspend fun deleteWebAppsExcept(keepWebAppIds: Set<WebAppId>) {
        dao.getAllWebApps()
            .map { WebAppId(it.webAppId) }
            .filterNot { it in keepWebAppIds }
            .forEach { deleteWebApp(it) }
    }

    suspend fun loadSessionState(webAppId: WebAppId): String? {
        return withContext(Dispatchers.IO) {
            val file = sessionStateFile(webAppId)
            if (file.exists()) file.readText().ifBlank { null } else null
        }
    }

    /**
     * 空の sessionState はファイルを削除する。
     * 削除済みのアプリのタスクが残っていても、ファイルを作り直して孤立させないよう登録が無ければ保存しない。
     */
    suspend fun saveSessionState(webAppId: WebAppId, sessionState: String) {
        sessionStateMutex.withLock {
            if (dao.getWebApp(webAppId.value) == null) return
            withContext(Dispatchers.IO) {
                val file = sessionStateFile(webAppId)
                if (sessionState.isBlank()) {
                    file.delete()
                } else {
                    sessionStateDir.mkdirs()
                    file.writeText(sessionState)
                }
            }
        }
    }

    private fun sessionStateFile(webAppId: WebAppId) = File(sessionStateDir, webAppId.value)

    private fun WebAppEntity.toWebAppData(): WebAppData {
        return WebAppData(
            id = WebAppId(webAppId),
            profileId = ProfileId(profileId),
            startUrl = startUrl,
            title = title,
        )
    }
}

data class WebAppData(
    val id: WebAppId,
    val profileId: ProfileId,
    val startUrl: String,
    val title: String,
)
