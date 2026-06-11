package net.matsudamper.browser.screen.sitesettings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.matsudamper.browser.TabSecurityInfo
import net.matsudamper.browser.data.SiteGeolocationState
import net.matsudamper.browser.data.SitePermissionState
import net.matsudamper.browser.data.SiteSettingsRepository
import net.matsudamper.browser.ui.settings.SiteSettingsScreenUiState

internal class SiteSettingsScreenViewModel(
    private val host: String,
    private val siteSettingsRepository: SiteSettingsRepository,
    securityInfo: TabSecurityInfo?,
) : ViewModel() {

    val eventHandler = Channel<(Event) -> Unit>(Channel.UNLIMITED)

    interface Event {
        /** OS の位置情報権限を要求する。結果は onLocationPermissionResult で受け取る */
        fun onRequestLocationPermission()
    }

    // 権限要求の多重発行を防ぐ in-flight フラグ
    private var isLocationPermissionRequestInFlight = false

    private val callbacks = object : SiteSettingsScreenUiState.Callbacks {
        override fun setMicrophonePermission(state: SitePermissionState) {
            viewModelScope.launch {
                siteSettingsRepository.setMicrophonePermission(host, state)
            }
        }

        override fun setGeolocationState(state: SiteGeolocationState) {
            // 実際の位置情報は OS の位置情報権限が必要なため、ここでは保存せず権限要求を行い、
            // 許可された場合のみ onLocationPermissionResult で保存する
            if (state == SiteGeolocationState.SITE_GEOLOCATION_REAL) {
                if (isLocationPermissionRequestInFlight) return
                isLocationPermissionRequestInFlight = true
                eventHandler.trySend { it.onRequestLocationPermission() }
                return
            }
            viewModelScope.launch {
                siteSettingsRepository.setGeolocationState(host, state)
            }
        }
    }

    /** OS の位置情報権限要求の結果。許可された場合のみ「実際の位置情報」を保存する */
    fun onLocationPermissionResult(granted: Boolean) {
        isLocationPermissionRequestInFlight = false
        if (!granted) return
        viewModelScope.launch {
            siteSettingsRepository.setGeolocationState(
                host = host,
                state = SiteGeolocationState.SITE_GEOLOCATION_REAL,
            )
        }
    }

    val uiState: StateFlow<SiteSettingsScreenUiState> = MutableStateFlow(
        SiteSettingsScreenUiState(
            callbacks = callbacks,
            host = host,
            microphonePermission = null,
            geolocationState = null,
            tlsCertificate = createTlsCertificateUiState(securityInfo),
        ),
    ).also { uiStateFlow ->
        viewModelScope.launch {
            siteSettingsRepository.requestedMicrophonePermission(host).collectLatest { permission ->
                uiStateFlow.update { it.copy(microphonePermission = permission) }
            }
        }
        viewModelScope.launch {
            siteSettingsRepository.requestedGeolocationState(host).collectLatest { state ->
                uiStateFlow.update { it.copy(geolocationState = state) }
            }
        }
    }.asStateFlow()

    private fun createTlsCertificateUiState(
        securityInfo: TabSecurityInfo?,
    ): SiteSettingsScreenUiState.TlsCertificate? {
        securityInfo ?: return null
        val certificate = securityInfo.certificate
        if (!securityInfo.isSecure || certificate == null) {
            return SiteSettingsScreenUiState.TlsCertificate.Insecure
        }
        val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
        return SiteSettingsScreenUiState.TlsCertificate.Available(
            subjectCommonName = extractCommonName(certificate.subjectX500Principal.name),
            issuer = extractCommonName(certificate.issuerX500Principal.name),
            validFrom = dateFormat.format(certificate.notBefore),
            validUntil = dateFormat.format(certificate.notAfter),
            sha256Fingerprint = sha256Fingerprint(certificate),
        )
    }

    /** DN から CN を抽出する。CN が無い場合は DN 全体を返す */
    private fun extractCommonName(distinguishedName: String): String {
        return Regex("""CN=([^,]+)""").find(distinguishedName)?.groupValues?.get(1)
            ?: distinguishedName
    }

    private fun sha256Fingerprint(certificate: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        return digest.joinToString(":") { "%02X".format(it) }
    }
}
