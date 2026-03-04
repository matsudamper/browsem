package net.matsudamper.browser.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataMigration
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStore
import androidx.datastore.dataStoreFile
import com.google.protobuf.ByteString
import kotlinx.coroutines.flow.Flow
import java.io.IOException

private val Context.browserTabDataStore: DataStore<BrowserTabData> by dataStore(
    fileName = "browser_tab_state.pb",
    serializer = BrowserTabDataSerializer,
    corruptionHandler = ReplaceFileCorruptionHandler { BrowserTabData.getDefaultInstance() },
    produceMigrations = { context ->
        listOf(LegacyBrowserSettingsTabMigration(context))
    },
)

class TabRepository(context: Context) {
    private val dataStore = context.browserTabDataStore

    val tabs: Flow<BrowserTabData> = dataStore.data

    suspend fun updateTabStates(
        tabs: List<PersistedTabState>,
        selectedTabIndex: Int,
    ) {
        dataStore.updateData { current ->
            val builder = current.toBuilder()
            val currentTabs = current.tabStatesList.map {
                PersistedTabState(
                    url = it.url,
                    sessionState = it.sessionState,
                    title = it.title,
                    previewImageWebp = it.previewImageWebp,
                    tabId = it.tabId,
                    openerTabId = it.openerTabId,
                )
            }
            if (currentTabs == tabs && current.selectedTabIndex == selectedTabIndex) {
                return@updateData current
            }
            builder.clearTabStates()
            tabs.forEach { tab ->
                builder.addTabStates(
                    BrowserTabState.newBuilder()
                        .setUrl(tab.url)
                        .setSessionState(tab.sessionState)
                        .setTitle(tab.title)
                        .setPreviewImageWebp(tab.previewImageWebp)
                        .setTabId(tab.tabId)
                        .setOpenerTabId(tab.openerTabId)
                        .build()
                )
            }
            builder.selectedTabIndex = selectedTabIndex
            builder.build()
        }
    }
}

data class PersistedTabState(
    val url: String,
    val sessionState: String,
    val title: String,
    val previewImageWebp: ByteString = ByteString.EMPTY,
    val tabId: String = "",
    val openerTabId: String = "",
)

private class LegacyBrowserSettingsTabMigration(
    private val context: Context,
) : DataMigration<BrowserTabData> {
    override suspend fun shouldMigrate(currentData: BrowserTabData): Boolean {
        if (currentData.tabStatesCount > 0 || currentData.selectedTabIndex != 0) {
            return false
        }
        val legacyData = readLegacySnapshot() ?: return false
        return legacyData.tabStatesCount > 0 || legacyData.selectedTabIndex != 0
    }

    override suspend fun migrate(currentData: BrowserTabData): BrowserTabData {
        if (currentData.tabStatesCount > 0 || currentData.selectedTabIndex != 0) {
            return currentData
        }
        val legacyData = readLegacySnapshot() ?: return currentData
        return BrowserTabData.newBuilder()
            .addAllTabStates(legacyData.tabStatesList)
            .setSelectedTabIndex(legacyData.selectedTabIndex)
            .build()
    }

    override suspend fun cleanUp() = Unit

    private fun readLegacySnapshot(): LegacyBrowserSettingsTabSnapshot? {
        val legacyFile = context.dataStoreFile("browser_settings.pb")
        if (!legacyFile.exists()) {
            return null
        }
        return try {
            legacyFile.inputStream().use { input ->
                LegacyBrowserSettingsTabSnapshot.parseFrom(input)
            }
        } catch (_: IOException) {
            null
        }
    }
}
