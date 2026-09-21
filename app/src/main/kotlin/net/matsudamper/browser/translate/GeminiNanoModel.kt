package net.matsudamper.browser.translate

import android.util.Log
import kotlinx.coroutines.CancellationException
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ModelConfig
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.generationConfig
import com.google.mlkit.genai.prompt.modelConfig

internal class SelectedGeminiNanoModel(
    val generativeModel: GenerativeModel,
    val key: String,
)

/**
 * 設定画面へ出す選択肢。
 *
 * 同じ表示モデル名でも世代・規模の指定ごとに挙動が変わるため、[key] は候補名と表示モデル名を連結する。
 */
internal class GeminiNanoModelOption(
    val key: String,
    val modelName: String,
    val downloaded: Boolean,
)

/**
 * 設定画面へ出す名前。ML Kit のモデル名に付く "[Preview, CPU]" のような実行環境の注記を落とす。
 */
internal fun toGeminiNanoModelLabel(modelName: String): String =
    modelName.substringBefore('[').trim().ifBlank { modelName }

/**
 * 保存済みのキーが今の一覧にない場合に、翻訳時の自動選択と同じ基準で選び直す。
 *
 * 一覧に無いキーのままだと設定画面でどの候補も選択されていない状態になる。
 */
internal fun resolveGeminiNanoModelKey(models: List<GeminiNanoModelOption>, savedKey: String): String {
    if (models.any { it.key == savedKey }) return savedKey
    val fallback = models.firstOrNull { it.downloaded } ?: models.firstOrNull()
    return fallback?.key.orEmpty()
}

/** 設定へ保存するキー。候補と表示モデル名の組を一意に表す */
internal fun geminiNanoModelKey(configName: String, modelName: String): String = "$configName/$modelName"

/**
 * 端末が持つ Gemini Nano を、世代（安定版・プレビュー版）と規模（FULL・FAST）の候補ごとに列挙する。
 *
 * 同じ表示モデル名へ解決される候補もそれぞれ別の選択肢として残す。
 */
internal suspend fun listGeminiNanoModels(): List<GeminiNanoModelOption> {
    return GEMINI_NANO_MODEL_CANDIDATES.mapNotNull { candidate ->
        val opened = openUsableGeminiNanoModel(candidate) ?: return@mapNotNull null
        opened.generativeModel.close()
        GeminiNanoModelOption(
            key = opened.key,
            modelName = opened.modelName,
            downloaded = opened.priority == DOWNLOADED_MODEL_PRIORITY,
        )
    }
}

/**
 * 翻訳に使う Gemini Nano を決める。
 *
 * [preferredKey] が空でなければ一致する候補を使い、一致しなければ候補順かつダウンロード済み優先で選んで
 * 追加ダウンロードを避ける。
 * 呼び出し側は返した [SelectedGeminiNanoModel.generativeModel] を閉じる責任を持つ。
 */
internal suspend fun selectGeminiNanoModel(preferredKey: String): SelectedGeminiNanoModel? {
    var selected: SelectedGeminiNanoModel? = null
    var selectedPriority = Int.MAX_VALUE
    var handedOver = false
    try {
        for (candidate in GEMINI_NANO_MODEL_CANDIDATES) {
            val opened = openUsableGeminiNanoModel(candidate) ?: continue
            if (preferredKey.isNotBlank() && opened.key == preferredKey) {
                selected?.generativeModel?.close()
                selected = SelectedGeminiNanoModel(opened.generativeModel, opened.key)
                break
            }
            if (opened.priority >= selectedPriority) {
                opened.generativeModel.close()
                continue
            }
            selected?.generativeModel?.close()
            selected = SelectedGeminiNanoModel(opened.generativeModel, opened.key)
            selectedPriority = opened.priority
            if (preferredKey.isBlank() && opened.priority == DOWNLOADED_MODEL_PRIORITY) break
        }
        handedOver = true
        return selected
    } finally {
        // 途中でキャンセルされた場合、まだ呼び出し側へ渡していないモデルはここで閉じる
        if (!handedOver) {
            selected?.generativeModel?.close()
        }
    }
}

private class OpenedGeminiNanoModel(
    val generativeModel: GenerativeModel,
    val modelName: String,
    val key: String,
    val priority: Int,
)

/** 使えない候補は閉じて null を返す */
private suspend fun openUsableGeminiNanoModel(candidate: GeminiNanoModelCandidate): OpenedGeminiNanoModel? {
    val generativeModel = createGeminiNanoModel(candidate) ?: return null
    var handedOver = false
    try {
        val priority = geminiNanoStatusPriority(checkGeminiNanoStatus(generativeModel)) ?: return null
        val modelName = fetchGeminiNanoModelName(generativeModel) ?: UNKNOWN_MODEL_NAME
        handedOver = true
        return OpenedGeminiNanoModel(
            generativeModel = generativeModel,
            modelName = modelName,
            key = geminiNanoModelKey(candidate.configName, modelName),
            priority = priority,
        )
    } finally {
        // 状態やモデル名の取得がキャンセルされても、開いたモデルを残さない
        if (!handedOver) {
            generativeModel.close()
        }
    }
}

private fun createGeminiNanoModel(candidate: GeminiNanoModelCandidate): GenerativeModel? {
    return try {
        Generation.getClient(
            generationConfig {
                modelConfig = candidate.modelConfig
            },
        )
    } catch (error: Exception) {
        Log.w(GEMINI_NANO_MODEL_TAG, "Gemini Nanoのモデル生成に失敗: ${candidate.configName}", error)
        null
    }
}

private suspend fun checkGeminiNanoStatus(generativeModel: GenerativeModel): Int? {
    return try {
        generativeModel.checkStatus()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Log.w(GEMINI_NANO_MODEL_TAG, "Gemini Nanoの利用状態を取得できなかった", error)
        null
    }
}

private suspend fun fetchGeminiNanoModelName(generativeModel: GenerativeModel): String? {
    return try {
        generativeModel.getBaseModelName().takeIf { it.isNotBlank() }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Log.w(GEMINI_NANO_MODEL_TAG, "Gemini Nanoの表示モデル名を取得できなかった", error)
        null
    }
}

/** 値が小さいほど優先する。未対応の状態は null */
internal fun geminiNanoStatusPriority(featureStatus: Int?): Int? = when (featureStatus) {
    FeatureStatus.AVAILABLE -> DOWNLOADED_MODEL_PRIORITY
    FeatureStatus.DOWNLOADING, FeatureStatus.DOWNLOADABLE -> UNDOWNLOADED_MODEL_PRIORITY
    else -> null
}

/**
 * 安定版・プレビュー版と FULL・FAST の全組み合わせ。既定の安定版 FULL を先に試す。
 *
 * [configName] は設定へ保存するキーの一部。
 */
internal enum class GeminiNanoModelCandidate(
    val configName: String,
    private val releaseStageId: Int,
    private val preferenceId: Int,
) {
    StableFull("stable-full", ModelReleaseStage.STABLE, ModelPreference.FULL),
    StableFast("stable-fast", ModelReleaseStage.STABLE, ModelPreference.FAST),
    PreviewFull("preview-full", ModelReleaseStage.PREVIEW, ModelPreference.FULL),
    PreviewFast("preview-fast", ModelReleaseStage.PREVIEW, ModelPreference.FAST),
    ;

    val modelConfig: ModelConfig = modelConfig {
        releaseStage = releaseStageId
        preference = preferenceId
    }
}

internal val GEMINI_NANO_MODEL_CANDIDATES: List<GeminiNanoModelCandidate> = GeminiNanoModelCandidate.entries

private const val GEMINI_NANO_MODEL_TAG = "GeminiNanoModel"
private const val UNKNOWN_MODEL_NAME = "unknown"
private const val DOWNLOADED_MODEL_PRIORITY = 0
private const val UNDOWNLOADED_MODEL_PRIORITY = 1

internal suspend fun isGeminiNanoAvailable(): Boolean {
    val selectedModel = selectGeminiNanoModel(preferredKey = "") ?: return false
    selectedModel.generativeModel.close()
    return true
}
