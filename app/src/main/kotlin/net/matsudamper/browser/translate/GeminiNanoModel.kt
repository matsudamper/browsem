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

/** 端末が持つ Gemini Nano のモデルを列挙・選択する */
internal object GeminiNanoModel {
    val CANDIDATES: List<Candidate> = Candidate.entries

    private const val TAG = "GeminiNanoModel"
    private const val UNKNOWN_MODEL_NAME = "unknown"
    private const val DOWNLOADED_MODEL_PRIORITY = 0
    private const val UNDOWNLOADED_MODEL_PRIORITY = 1

    /**
     * 設定画面へ出す名前。ML Kit のモデル名に付く "[Preview, CPU]" のような実行環境の注記を落とす。
     */
    fun toLabel(modelName: String): String {
        return modelName
            .substringBefore('[').trim()
            .ifBlank { modelName }
    }

    /**
     * 保存済みのキーが今の一覧にない場合に、翻訳時の自動選択と同じ基準で選び直す。
     *
     * 一覧に無いキーのままだと設定画面でどの候補も選択されていない状態になる。
     */
    fun resolveKey(models: List<Option>, savedKey: String): String {
        if (models.any { it.key == savedKey }) return savedKey
        val fallback = models.firstOrNull { it.downloaded } ?: models.firstOrNull()
        return fallback?.key.orEmpty()
    }

    /**
     * 端末が持つ Gemini Nano を、世代（安定版・プレビュー版）と規模（FULL・FAST）の候補ごとに列挙する。
     *
     * 同じ表示モデル名へ解決される候補もそれぞれ別の選択肢として残す。
     */
    suspend fun list(): List<Option> {
        return CANDIDATES.mapNotNull { candidate ->
            val opened = openUsable(candidate) ?: return@mapNotNull null
            opened.generativeModel.close()
            Option(
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
     * 呼び出し側は返した [Selected.generativeModel] を閉じる責任を持つ。
     */
    suspend fun select(preferredKey: String): Selected? {
        var selected: Selected? = null
        var selectedPriority = Int.MAX_VALUE
        var handedOver = false
        try {
            for (candidate in CANDIDATES) {
                val opened = openUsable(candidate) ?: continue
                if (preferredKey.isNotBlank() && opened.key == preferredKey) {
                    selected?.generativeModel?.close()
                    selected = Selected(opened.generativeModel, opened.key)
                    break
                }
                if (opened.priority >= selectedPriority) {
                    opened.generativeModel.close()
                    continue
                }
                selected?.generativeModel?.close()
                selected = Selected(opened.generativeModel, opened.key)
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

    /** 値が小さいほど優先する。未対応の状態は null */
    fun statusPriority(featureStatus: Int?): Int? = when (featureStatus) {
        FeatureStatus.AVAILABLE -> DOWNLOADED_MODEL_PRIORITY
        FeatureStatus.DOWNLOADING, FeatureStatus.DOWNLOADABLE -> UNDOWNLOADED_MODEL_PRIORITY
        else -> null
    }

    /** 設定へ保存するキー。候補と表示モデル名の組を一意に表す */
    fun buildKey(configName: String, modelName: String): String = "$configName/$modelName"

    /** 使えない候補は閉じて null を返す */
    private suspend fun openUsable(candidate: Candidate): Opened? {
        val generativeModel = create(candidate) ?: return null
        var handedOver = false
        try {
            val priority = statusPriority(checkStatus(generativeModel)) ?: return null
            val modelName = fetchName(generativeModel) ?: UNKNOWN_MODEL_NAME
            handedOver = true
            return Opened(
                generativeModel = generativeModel,
                modelName = "Local $modelName",
                key = buildKey(candidate.configName, modelName),
                priority = priority,
            )
        } finally {
            // 状態やモデル名の取得がキャンセルされても、開いたモデルを残さない
            if (!handedOver) {
                generativeModel.close()
            }
        }
    }

    private fun create(candidate: Candidate): GenerativeModel? {
        return try {
            Generation.getClient(
                generationConfig {
                    modelConfig = candidate.modelConfig
                },
            )
        } catch (error: Exception) {
            Log.w(TAG, "Gemini Nanoのモデル生成に失敗: ${candidate.configName}", error)
            null
        }
    }

    private suspend fun checkStatus(generativeModel: GenerativeModel): Int? {
        return try {
            generativeModel.checkStatus()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Gemini Nanoの利用状態を取得できなかった", error)
            null
        }
    }

    private suspend fun fetchName(generativeModel: GenerativeModel): String? {
        return try {
            generativeModel.getBaseModelName().takeIf { it.isNotBlank() }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Gemini Nanoの表示モデル名を取得できなかった", error)
            null
        }
    }

    /**
     * 安定版・プレビュー版と FULL・FAST の全組み合わせ。既定の安定版 FULL を先に試す。
     *
     * [configName] は設定へ保存するキーの一部。
     */
    enum class Candidate(
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

    /**
     * 設定画面へ出す選択肢。
     *
     * 同じ表示モデル名でも世代・規模の指定ごとに挙動が変わるため、[key] は候補名と表示モデル名を連結する。
     */
    class Option(
        val key: String,
        val modelName: String,
        val downloaded: Boolean,
    )

    class Selected(
        val generativeModel: GenerativeModel,
        val key: String,
    )

    private class Opened(
        val generativeModel: GenerativeModel,
        val modelName: String,
        val key: String,
        val priority: Int,
    )
}
