package net.matsudamper.browser.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStore
import java.net.URI
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.siteSettingsDataStore: DataStore<SiteSettings> by dataStore(
    fileName = "site_settings.pb",
    serializer = SiteSettingsSerializer,
    corruptionHandler = ReplaceFileCorruptionHandler { SiteSettings.getDefaultInstance() },
)

/** サイト（ホスト）ごとの設定を保存するリポジトリ */
class SiteSettingsRepository(context: Context) {
    private val dataStore = context.siteSettingsDataStore

    /** 指定ホストのマイク権限の状態を監視する。未設定の場合は ASK を返す */
    fun microphonePermission(host: String): Flow<SitePermissionState> {
        return dataStore.data
            .map { settings ->
                settings.hostPermissionsMap[host]?.microphone
                    ?: SitePermissionState.SITE_PERMISSION_ASK
            }
            .distinctUntilChanged()
    }

    /**
     * 指定ホストのマイク権限の状態を監視する。
     * サイトから一度も要求されていない場合は null を返す
     */
    fun requestedMicrophonePermission(host: String): Flow<SitePermissionState?> {
        return dataStore.data
            .map { settings ->
                val permissions = settings.hostPermissionsMap[host] ?: return@map null
                // microphoneRequested 追加前に保存された ALLOW/DENY も要求済みとして扱う
                if (permissions.microphoneRequested ||
                    permissions.microphone != SitePermissionState.SITE_PERMISSION_ASK
                ) {
                    permissions.microphone
                } else {
                    null
                }
            }
            .distinctUntilChanged()
    }

    /** 指定ホストがマイク権限を要求したことを記録する */
    suspend fun markMicrophonePermissionRequested(host: String) {
        dataStore.updateData { current ->
            val permissions = current.hostPermissionsMap[host] ?: SitePermissionSettings.getDefaultInstance()
            if (permissions.microphoneRequested) return@updateData current
            current.toBuilder()
                .putHostPermissions(
                    host,
                    permissions.toBuilder().setMicrophoneRequested(true).build(),
                )
                .build()
        }
    }

    /** 指定ホストの現在のマイク権限の状態を取得する */
    suspend fun getMicrophonePermission(host: String): SitePermissionState {
        return microphonePermission(host).first()
    }

    suspend fun setMicrophonePermission(host: String, state: SitePermissionState) {
        dataStore.updateData { current ->
            val permissions = (current.hostPermissionsMap[host] ?: SitePermissionSettings.getDefaultInstance())
                .toBuilder()
                .setMicrophone(state)
                .build()
            current.toBuilder()
                .putHostPermissions(host, permissions)
                .build()
        }
    }
}

/** URL からサイト設定のキーとなるホスト名を取り出す。取得できない場合は null */
fun extractSiteHost(url: String): String? {
    return runCatching { URI(url) }.getOrNull()?.host
}
