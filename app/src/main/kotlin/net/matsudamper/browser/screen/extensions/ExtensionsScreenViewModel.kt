package net.matsudamper.browser.screen.extensions

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.matsudamper.browser.applyAllowUnsignedExtensions
import net.matsudamper.browser.awaitGecko
import net.matsudamper.browser.data.SettingsRepository
import net.matsudamper.browser.data.resolvedAllowUnsignedExtensions
import net.matsudamper.browser.isSignedWebExtensionArchive
import net.matsudamper.browser.isWebExtensionArchive
import net.matsudamper.browser.ui.extensions.ExtensionsScreenUiState
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import java.io.File
import java.io.IOException

internal class ExtensionsScreenViewModel(
    application: Application,
    private val runtime: GeckoRuntime,
    private val settingsRepository: SettingsRepository,
) : AndroidViewModel(application) {

    private val viewModelStateFlow = MutableStateFlow(ViewModelState())
    val eventHandler = Channel<(Event) -> Unit>(Channel.UNLIMITED)

    private val callbacks = object : ExtensionsScreenUiState.Callbacks {
        override fun refreshExtensions() {
            loadExtensions()
        }

        override fun installExtensionFromFile() {
            if (viewModelStateFlow.value.isInstalling) return
            eventHandler.trySend { it.requestExtensionFilePicker() }
        }

        override fun confirmUnsignedInstall() {
            val archiveFile = viewModelStateFlow.value.unsignedArchiveFile ?: return
            viewModelStateFlow.update {
                it.copy(unsignedArchiveFile = null, isInstalling = true)
            }
            viewModelScope.launch {
                // 署名検証は起動時にも行われるため、設定を保存して以降の起動にも反映する。
                // 必須に戻すとインストール済みの署名なし拡張機能は無効化される。
                settingsRepository.setAllowUnsignedExtensions(true)
                val applied = runCatching {
                    applyAllowUnsignedExtensions(allowUnsigned = true).awaitGecko()
                }.isSuccess
                if (!applied) {
                    deleteQuietly(archiveFile)
                    viewModelStateFlow.update {
                        it.copy(
                            isInstalling = false,
                            errorMessage = "署名検証の設定変更に失敗したため、インストールを中止しました。",
                        )
                    }
                    return@launch
                }
                install(archiveFile)
            }
        }

        override fun dismissUnsignedInstall() {
            val archiveFile = viewModelStateFlow.value.unsignedArchiveFile ?: return
            viewModelStateFlow.update { it.copy(unsignedArchiveFile = null) }
            viewModelScope.launch {
                withContext(Dispatchers.IO) { deleteQuietly(archiveFile) }
            }
        }

        override fun uninstallExtension(extensionId: String) {
            val extension = viewModelStateFlow.value.extensions
                .firstOrNull { it.id == extensionId } ?: return
            viewModelStateFlow.update { it.copy(uninstallingId = extensionId) }
            runtime.webExtensionController.uninstall(extension).accept(
                {
                    viewModelStateFlow.update { it.copy(uninstallingId = null) }
                    loadExtensions()
                },
                { error ->
                    viewModelStateFlow.update {
                        it.copy(
                            uninstallingId = null,
                            errorMessage = error?.message ?: "拡張機能のアンインストールに失敗しました。",
                        )
                    }
                },
            )
        }

        override fun setExtensionEnabled(extensionId: String, enabled: Boolean) {
            val currentState = viewModelStateFlow.value
            if (currentState.togglingId != null || currentState.uninstallingId != null) return
            val extension = currentState.extensions
                .firstOrNull { it.id == extensionId } ?: return
            viewModelStateFlow.update { it.copy(togglingId = extensionId) }
            val result = if (enabled) {
                runtime.webExtensionController.enable(extension, WebExtensionController.EnableSource.USER)
            } else {
                runtime.webExtensionController.disable(extension, WebExtensionController.EnableSource.USER)
            }
            result.accept(
                { updatedExtension ->
                    viewModelStateFlow.update { state ->
                        state.copy(
                            togglingId = null,
                            extensions = state.extensions.map { ext ->
                                if (ext.id == extensionId && updatedExtension != null) updatedExtension else ext
                            },
                        )
                    }
                },
                { error ->
                    viewModelStateFlow.update {
                        it.copy(
                            togglingId = null,
                            errorMessage = error?.message ?: "拡張機能の切り替えに失敗しました。",
                        )
                    }
                },
            )
        }

        override fun openExtensionSettings(extensionId: String) {
            val extension = viewModelStateFlow.value.extensions
                .firstOrNull { it.id == extensionId } ?: return
            val optionsPageUrl = extension.metaData.optionsPageUrl?.takeIf { it.isNotBlank() }
            if (optionsPageUrl != null) {
                eventHandler.trySend { it.navigateToExtensionSettings(optionsPageUrl) }
            } else {
                viewModelStateFlow.update { it.copy(errorMessage = "この拡張機能には設定画面がありません。") }
            }
        }

        override fun dismissError() {
            viewModelStateFlow.update { it.copy(errorMessage = null) }
        }
    }

    val uiState: StateFlow<ExtensionsScreenUiState> = MutableStateFlow(
        ExtensionsScreenUiState(
            callbacks = callbacks,
            loadingState = ExtensionsScreenUiState.LoadingState.Loading,
            errorMessage = null,
            uninstallingId = null,
            togglingId = null,
            isInstalling = false,
            unsignedInstallConfirmation = null,
        )
    ).also { uiStateFlow ->
        viewModelScope.launch {
            viewModelStateFlow.collectLatest { state ->
                uiStateFlow.update {
                    ExtensionsScreenUiState(
                        callbacks = callbacks,
                        loadingState = if (state.isLoading) {
                            ExtensionsScreenUiState.LoadingState.Loading
                        } else {
                            ExtensionsScreenUiState.LoadingState.Loaded(
                                extensions = state.extensions.map { ext ->
                                    ExtensionsScreenUiState.ExtensionUiState(
                                        id = ext.id,
                                        displayName = ext.metaData.name?.takeIf { it.isNotBlank() } ?: ext.id,
                                        version = ext.metaData.version,
                                        hasSettingsPage = ext.metaData.optionsPageUrl?.isNotBlank() == true,
                                        isEnabled = ext.metaData.enabled,
                                        isBuiltIn = ext.isBuiltIn,
                                    )
                                },
                            )
                        },
                        errorMessage = state.errorMessage,
                        uninstallingId = state.uninstallingId,
                        togglingId = state.togglingId,
                        isInstalling = state.isInstalling,
                        unsignedInstallConfirmation = state.unsignedArchiveFile?.let {
                            ExtensionsScreenUiState.UnsignedInstallConfirmation(
                                message = UNSIGNED_INSTALL_WARNING_MESSAGE,
                            )
                        },
                    )
                }
            }
        }
    }.asStateFlow()

    init {
        loadExtensions()
    }

    /**
     * ファイルピッカーで選択された ZIP / XPI をインストールする。
     * キャンセル時は uri が null で呼ばれる。
     */
    fun onExtensionFileSelected(uri: Uri?) {
        if (uri == null) return
        if (viewModelStateFlow.value.isInstalling) return
        viewModelStateFlow.update { it.copy(isInstalling = true, errorMessage = null) }
        viewModelScope.launch {
            val archiveFile = runCatching {
                withContext(Dispatchers.IO) { copyToInstallCache(uri) }
            }.getOrElse { error ->
                viewModelStateFlow.update {
                    it.copy(
                        isInstalling = false,
                        errorMessage = "拡張機能ファイルの読み込みに失敗しました。\n${error.message.orEmpty()}",
                    )
                }
                return@launch
            }
            val isExtensionArchive = withContext(Dispatchers.IO) { isWebExtensionArchive(archiveFile) }
            if (!isExtensionArchive) {
                withContext(Dispatchers.IO) { deleteQuietly(archiveFile) }
                viewModelStateFlow.update {
                    it.copy(
                        isInstalling = false,
                        errorMessage = "選択したファイルは拡張機能ではありません。\nmanifest.json を含む ZIP / XPI を選択してください。",
                    )
                }
                return@launch
            }
            val isSigned = withContext(Dispatchers.IO) { isSignedWebExtensionArchive(archiveFile) }
            val allowUnsigned = settingsRepository.settings.first().resolvedAllowUnsignedExtensions()
            if (!isSigned && !allowUnsigned) {
                // 署名なしはそのままではインストールできないため、警告に同意した場合のみ続行する
                viewModelStateFlow.update {
                    it.copy(isInstalling = false, unsignedArchiveFile = archiveFile)
                }
                return@launch
            }
            install(archiveFile)
        }
    }

    /**
     * ローカルファイルからインストールする。
     * GeckoView の install() は file:// URI を受け付け、署名検証やインストール確認ダイアログ
     * (PromptDelegate) は AMO からのインストールと同じ経路で処理される。
     */
    private fun install(archiveFile: File) {
        runtime.webExtensionController.install(
            Uri.fromFile(archiveFile).toString(),
            WebExtensionController.INSTALLATION_METHOD_MANAGER,
        ).accept(
            {
                deleteQuietly(archiveFile)
                viewModelStateFlow.update { it.copy(isInstalling = false) }
                loadExtensions()
            },
            { error ->
                deleteQuietly(archiveFile)
                viewModelStateFlow.update {
                    it.copy(
                        isInstalling = false,
                        // InstallException (署名なし・非対応など) はアプリ全体のインストール失敗
                        // ダイアログ (MainActivity の WebExtensionInstaller) が AddonManagerDelegate
                        // 経由で表示するため、二重表示を避けてここではメッセージを出さない。
                        errorMessage = if (error is WebExtension.InstallException) {
                            it.errorMessage
                        } else {
                            "拡張機能のインストールに失敗しました。\n${error?.message.orEmpty()}"
                        },
                    )
                }
            },
        )
    }

    /**
     * SAF の Uri は Gecko 側から読めないため、アプリのキャッシュへコピーして file:// で渡す。
     * GeckoView (AddonManager) は拡張子 .xpi / .zip のみをアーカイブとして扱うため .xpi で保存する。
     */
    private fun copyToInstallCache(uri: Uri): File {
        val context = getApplication<Application>()
        val cacheDir = File(context.cacheDir, EXTENSION_INSTALL_CACHE_DIR)
        cacheDir.mkdirs()
        // インストール途中でプロセスが落ちた場合に備え、残っているファイルを削除する
        cacheDir.listFiles()?.forEach { it.delete() }
        val destination = File(cacheDir, "extension-${System.currentTimeMillis()}.xpi")
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("ファイルを開けませんでした: $uri")
        inputStream.use { input ->
            destination.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return destination
    }

    private fun deleteQuietly(file: File) {
        runCatching { file.delete() }
    }

    private fun loadExtensions() {
        viewModelStateFlow.update { it.copy(isLoading = true) }
        runtime.webExtensionController.list().accept(
            { list ->
                viewModelStateFlow.update {
                    it.copy(
                        isLoading = false,
                        extensions = (list ?: emptyList()).sortedBy { ext ->
                            (ext.metaData.name?.takeIf { n -> n.isNotBlank() } ?: ext.id).lowercase()
                        },
                    )
                }
            },
            { error ->
                viewModelStateFlow.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = error?.message ?: "拡張機能一覧の取得に失敗しました。",
                    )
                }
            },
        )
    }

    interface Event {
        fun navigateToExtensionSettings(url: String)

        /** ZIP / XPI を選択するファイルピッカーを開く */
        fun requestExtensionFilePicker()
    }

    data class ViewModelState(
        val extensions: List<WebExtension> = emptyList(),
        val isLoading: Boolean = true,
        val uninstallingId: String? = null,
        val togglingId: String? = null,
        val isInstalling: Boolean = false,
        // 署名なしの警告に同意されるまで保持するインストール対象ファイル
        val unsignedArchiveFile: File? = null,
        val errorMessage: String? = null,
    )

    companion object {
        private const val EXTENSION_INSTALL_CACHE_DIR = "extension_install"

        private const val UNSIGNED_INSTALL_WARNING_MESSAGE =
            "このファイルは署名されていません。\n\n" +
                "署名されていない拡張機能は改ざんされている可能性があり、" +
                "閲覧内容やアカウント情報が盗まれる危険があります。" +
                "信頼できる提供元のファイルのみインストールしてください。\n\n" +
                "続行すると拡張機能の署名検証が無効になり、以降にインストールする拡張機能にも適用されます。"

        /** ファイルピッカーで選択可能にする MIME タイプ */
        val EXTENSION_ARCHIVE_MIME_TYPES = arrayOf(
            "application/zip",
            "application/x-xpinstall",
            // 提供元によっては ZIP / XPI が octet-stream や不明な MIME で返るため広く許可する
            "application/octet-stream",
            "*/*",
        )
    }
}
