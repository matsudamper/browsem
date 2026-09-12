package net.matsudamper.browser.data

import android.content.Context
import androidx.room.withTransaction
import java.io.File
import java.net.URI
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.matsudamper.browser.data.sitesettings.SiteSettingsDatabase
import net.matsudamper.browser.data.sitesettings.SiteSettingsEntity

/** サイト（ホスト）ごとの設定を保存するリポジトリ */
class SiteSettingsRepository(context: Context) {
    private val applicationContext = context.applicationContext
    private val database = SiteSettingsDatabase.getInstance(applicationContext)
    private val dao = database.siteSettingsDao()
    private val legacyFile = File(applicationContext.filesDir, "datastore/site_settings.pb")
    private val migrationMutex = Mutex()

    @Volatile
    private var legacyMigrationChecked = false

    /** サイト別設定が保存されているホスト一覧を監視する */
    fun siteHosts(): Flow<List<String>> {
        return migratedFlow { dao.observeHosts() }
    }

    /** サイト別設定が保存されているホストを検索し、指定範囲を監視する */
    fun siteHosts(query: String, limit: Int, offset: Int): Flow<List<String>> {
        return migratedFlow {
            dao.observeHosts(
                query = query.trim(),
                limit = limit,
                offset = offset,
            )
        }
    }

    /** 指定ホストのマイク権限の状態を監視する。未設定の場合は ASK を返す */
    fun microphonePermission(host: String): Flow<SitePermissionState> {
        return migratedFlow {
            dao.observe(host).map { settings -> settings?.microphone.toSitePermissionState() }
        }.distinctUntilChanged()
    }

    /**
     * 指定ホストのマイク権限の状態を監視する。
     * サイトから一度も要求されていない場合は null を返す
     */
    fun requestedMicrophonePermission(host: String): Flow<SitePermissionState?> {
        return migratedFlow {
            dao.observe(host).map { settings ->
                if (settings == null) {
                    null
                } else {
                    val state = settings.microphone.toSitePermissionState()
                    if (settings.microphoneRequested || state != SitePermissionState.SITE_PERMISSION_ASK) {
                        state
                    } else {
                        null
                    }
                }
            }
        }.distinctUntilChanged()
    }

    /** 指定ホストがマイク権限を要求したことを記録する */
    suspend fun markMicrophonePermissionRequested(host: String) {
        ensureLegacyMigration()
        database.withTransaction {
            dao.ensureHost(host)
            dao.markMicrophonePermissionRequested(host)
        }
    }

    /** 指定ホストの現在のマイク権限の状態を取得する */
    suspend fun getMicrophonePermission(host: String): SitePermissionState {
        return microphonePermission(host).first()
    }

    suspend fun setMicrophonePermission(host: String, state: SitePermissionState) {
        ensureLegacyMigration()
        database.withTransaction {
            dao.ensureHost(host)
            dao.updateMicrophonePermission(host, state.toStoredValue())
        }
    }

    /**
     * 指定ホストの音声付きメディアの自動再生の状態を監視する。未設定の場合は ASK を返す。
     * 消音メディアの自動再生は常に許可するため、この設定の対象外
     */
    fun autoplayPermission(host: String): Flow<SitePermissionState> {
        return migratedFlow {
            dao.observe(host).map { settings -> settings?.autoplay.toSitePermissionState() }
        }.distinctUntilChanged()
    }

    /**
     * 指定ホストの音声付きメディアの自動再生の状態を監視する。
     * サイトから一度も要求されていない場合は null を返す
     */
    fun requestedAutoplayPermission(host: String): Flow<SitePermissionState?> {
        return migratedFlow {
            dao.observe(host).map { settings ->
                if (settings?.autoplayRequested == true) {
                    settings.autoplay.toSitePermissionState()
                } else {
                    null
                }
            }
        }.distinctUntilChanged()
    }

    /** 指定ホストが音声付きメディアの自動再生を要求したことを記録する */
    suspend fun markAutoplayPermissionRequested(host: String) {
        ensureLegacyMigration()
        database.withTransaction {
            dao.ensureHost(host)
            dao.markAutoplayPermissionRequested(host)
        }
    }

    /** 指定ホストの現在の音声付きメディアの自動再生の状態を取得する */
    suspend fun getAutoplayPermission(host: String): SitePermissionState {
        return autoplayPermission(host).first()
    }

    suspend fun setAutoplayPermission(host: String, state: SitePermissionState) {
        ensureLegacyMigration()
        database.withTransaction {
            dao.ensureHost(host)
            dao.updateAutoplayPermission(host, state.toStoredValue())
        }
    }

    /** 指定ホストの位置情報の扱いを監視する。未設定の場合は MOCK を返す */
    fun geolocationState(host: String): Flow<SiteGeolocationState> {
        return migratedFlow {
            dao.observe(host).map { settings -> settings?.geolocation.toSiteGeolocationState() }
        }.distinctUntilChanged()
    }

    /** 指定ホストの現在の位置情報の扱いを取得する */
    suspend fun getGeolocationState(host: String): SiteGeolocationState {
        return geolocationState(host).first()
    }

    /** 全ホストの位置情報の扱いを監視する。key はホスト名 */
    fun geolocationStates(): Flow<Map<String, SiteGeolocationState>> {
        return migratedFlow {
            dao.observeAll().map { settings ->
                settings.associate { item -> item.host to item.geolocation.toSiteGeolocationState() }
            }
        }.distinctUntilChanged()
    }

    /**
     * 指定ホストの位置情報の扱いを監視する。
     * サイトから一度も要求されていない場合は null を返す
     */
    fun requestedGeolocationState(host: String): Flow<SiteGeolocationState?> {
        return migratedFlow {
            dao.observe(host).map { settings ->
                if (settings == null) {
                    null
                } else {
                    val state = settings.geolocation.toSiteGeolocationState()
                    if (settings.geolocationRequested || state != SiteGeolocationState.SITE_GEOLOCATION_MOCK) {
                        state
                    } else {
                        null
                    }
                }
            }
        }.distinctUntilChanged()
    }

    /** 指定ホストが位置情報を要求したことを記録する */
    suspend fun markGeolocationRequested(host: String) {
        ensureLegacyMigration()
        database.withTransaction {
            dao.ensureHost(host)
            dao.markGeolocationRequested(host)
        }
    }

    suspend fun setGeolocationState(host: String, state: SiteGeolocationState) {
        ensureLegacyMigration()
        database.withTransaction {
            dao.ensureHost(host)
            dao.updateGeolocationState(host, state.toStoredValue())
        }
    }

    private fun <T> migratedFlow(source: () -> Flow<T>): Flow<T> {
        return flow {
            ensureLegacyMigration()
            emitAll(source())
        }
    }

    private suspend fun ensureLegacyMigration() {
        if (legacyMigrationChecked) return
        migrationMutex.withLock {
            if (!legacyMigrationChecked) {
                if (!dao.isLegacyMigrationCompleted()) {
                    val legacySettings = readLegacySettings()
                    val legacyEntities = legacySettings.hostPermissionsMap.map { (host, permissions) ->
                        SiteSettingsEntity(
                            host = host,
                            microphone = permissions.microphoneValue,
                            microphoneRequested = permissions.microphoneRequested,
                            geolocation = permissions.geolocationValue,
                            geolocationRequested = permissions.geolocationRequested,
                            autoplay = permissions.autoplayValue,
                            autoplayRequested = permissions.autoplayRequested,
                        )
                    }
                    database.withTransaction {
                        if (!dao.isLegacyMigrationCompleted()) {
                            dao.upsertAll(legacyEntities)
                            dao.markLegacyMigrationCompleted()
                        }
                    }
                }
                legacyMigrationChecked = true
            }
        }
    }

    private fun readLegacySettings(): SiteSettings {
        if (!legacyFile.exists()) return SiteSettings.getDefaultInstance()
        // 移行元ファイルはロールバックや調査に使えるよう、移行後も削除・更新しない。
        return legacyFile.inputStream().use { inputStream -> SiteSettings.parseFrom(inputStream) }
    }
}

private fun Int?.toSitePermissionState(): SitePermissionState {
    return when (this) {
        1 -> SitePermissionState.SITE_PERMISSION_ALLOW
        2 -> SitePermissionState.SITE_PERMISSION_DENY
        else -> SitePermissionState.SITE_PERMISSION_ASK
    }
}

private fun Int?.toSiteGeolocationState(): SiteGeolocationState {
    return when (this) {
        1 -> SiteGeolocationState.SITE_GEOLOCATION_DENY
        2 -> SiteGeolocationState.SITE_GEOLOCATION_REAL
        else -> SiteGeolocationState.SITE_GEOLOCATION_MOCK
    }
}

private fun SitePermissionState.toStoredValue(): Int {
    return when (this) {
        SitePermissionState.SITE_PERMISSION_ALLOW -> 1
        SitePermissionState.SITE_PERMISSION_DENY -> 2
        else -> 0
    }
}

private fun SiteGeolocationState.toStoredValue(): Int {
    return when (this) {
        SiteGeolocationState.SITE_GEOLOCATION_DENY -> 1
        SiteGeolocationState.SITE_GEOLOCATION_REAL -> 2
        else -> 0
    }
}

/** URL からサイト設定のキーとなるホスト名を取り出す。取得できない場合は null */
fun extractSiteHost(url: String): String? {
    return runCatching { URI(url) }.getOrNull()?.host
}
