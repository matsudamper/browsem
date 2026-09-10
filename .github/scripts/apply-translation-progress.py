from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text()
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected exactly one match, found {count}: {old[:120]!r}")
    file.write_text(text.replace(old, new, 1))


translator = "app/src/main/kotlin/net/matsudamper/browser/translate/Translator.kt"
replace_once(
    translator,
    """    enum class TranslateState {
        MODEL_DOWNLOAD,
    }
""",
    """    enum class TranslateState {
        PAGE_SCAN,
        LANGUAGE_DETECTION,
        MODEL_DOWNLOAD,
        TRANSLATING,
    }
""",
)

status_bar = "app/src/main/kotlin/net/matsudamper/browser/TranslationStatusBar.kt"
replace_once(
    status_bar,
    "import androidx.compose.ui.res.painterResource\nimport androidx.compose.ui.unit.dp",
    "import androidx.compose.ui.res.painterResource\nimport androidx.compose.ui.tooling.preview.Preview\nimport androidx.compose.ui.unit.dp",
)
replace_once(
    status_bar,
    "import java.util.Locale\nimport net.matsudamper.browser.resources.R as ResourcesR",
    "import java.util.Locale\nimport net.matsudamper.browser.data.ThemeMode\nimport net.matsudamper.browser.resources.R as ResourcesR\nimport net.matsudamper.browser.ui.common.BrowserTheme",
)
replace_once(
    status_bar,
    "internal enum class TranslationState { Idle, Loading, Translated, Error }",
    """internal enum class TranslationState {
    Idle,
    Loading,
    ScanningPage,
    DetectingLanguage,
    PreparingModel,
    Translating,
    Translated,
    Error,
}

internal val TranslationState.isInProgress: Boolean
    get() = when (this) {
        TranslationState.Loading,
        TranslationState.ScanningPage,
        TranslationState.DetectingLanguage,
        TranslationState.PreparingModel,
        TranslationState.Translating,
        -> true

        TranslationState.Idle,
        TranslationState.Translated,
        TranslationState.Error,
        -> false
    }""",
)
replace_once(
    status_bar,
    """    val backgroundColor = when (state) {
        TranslationState.Loading,
        TranslationState.Translated,
        -> MaterialTheme.colorScheme.secondaryContainer

        TranslationState.Error -> MaterialTheme.colorScheme.errorContainer

        TranslationState.Idle -> return
    }
""",
    """    val backgroundColor = when (state) {
        TranslationState.Loading,
        TranslationState.ScanningPage,
        TranslationState.DetectingLanguage,
        TranslationState.PreparingModel,
        TranslationState.Translating,
        TranslationState.Translated,
        -> MaterialTheme.colorScheme.secondaryContainer

        TranslationState.Error -> MaterialTheme.colorScheme.errorContainer

        TranslationState.Idle -> return
    }
""",
)
replace_once(status_bar, "if (state == TranslationState.Loading) {", "if (state.isInProgress) {")
replace_once(
    status_bar,
    "                    TranslationState.Loading -> {\n",
    """                    TranslationState.Loading,
                    TranslationState.ScanningPage,
                    TranslationState.DetectingLanguage,
                    TranslationState.PreparingModel,
                    TranslationState.Translating,
                    -> {
""",
)
replace_once(status_bar, '                            text = "翻訳中...",', '                            text = translationProgressLabel(state),')
replace_once(
    status_bar,
    "/** 言語タグを表示名で示すTextButton。クリックでDropdownMenuを展開する。 */",
    """internal fun translationProgressLabel(state: TranslationState): String = when (state) {
    TranslationState.Loading -> "翻訳を開始中..."

    TranslationState.ScanningPage -> "ページを解析中..."

    TranslationState.DetectingLanguage -> "翻訳言語を確認中..."

    TranslationState.PreparingModel -> "ML翻訳モデルを準備中..."

    TranslationState.Translating -> "翻訳中..."

    TranslationState.Idle,
    TranslationState.Translated,
    TranslationState.Error,
    -> ""
}

/** 言語タグを表示名で示すTextButton。クリックでDropdownMenuを展開する。 */""",
)
status_path = Path(status_bar)
status_path.write_text(
    status_path.read_text()
    + """

@Preview(name = "ML翻訳モデル準備中", widthDp = 360)
@Composable
private fun PreviewTranslationStatusBarPreparingModel() {
    BrowserTheme(themeMode = ThemeMode.THEME_LIGHT) {
        TranslationStatusBar(
            state = TranslationState.PreparingModel,
            onRevert = {},
            onDismissError = {},
        )
    }
}
"""
)

page_translator = "app/src/main/kotlin/net/matsudamper/browser/PageTranslator.kt"
replace_once(
    page_translator,
    "import net.matsudamper.browser.translate.TranslationPriorityLanguage\nimport org.mozilla.geckoview.GeckoSession",
    "import net.matsudamper.browser.translate.TranslationPriorityLanguage\nimport net.matsudamper.browser.translate.Translator\nimport org.mozilla.geckoview.GeckoSession",
)
replace_once(
    page_translator,
    """        toLanguage: String,
    ): TranslationLanguages? {
""",
    """        toLanguage: String,
        onTranslateStateChanged: (Translator.TranslateState) -> Unit,
    ): TranslationLanguages? {
""",
)
replace_once(
    page_translator,
    "            GeckoTranslator(session, effectiveFrom, effectiveTo)",
    """            onTranslateStateChanged(Translator.TranslateState.TRANSLATING)
            GeckoTranslator(session, effectiveFrom, effectiveTo)""",
)
replace_once(
    page_translator,
    "LocalAITranslator(session, fromLanguage, toLanguage)",
    """LocalAITranslator(
                session = session,
                fromLanguage = fromLanguage,
                toLanguage = toLanguage,
                onTranslateStateChanged = onTranslateStateChanged,
            )""",
)

local_ai = "app/src/main/kotlin/net/matsudamper/browser/translate/LocalAITranslator.kt"
replace_once(
    local_ai,
    """    private val toLanguage: String,
) : Translator, KoinComponent {
""",
    """    private val toLanguage: String,
    private val onTranslateStateChanged: (Translator.TranslateState) -> Unit,
) : Translator, KoinComponent {
""",
)
replace_once(
    local_ai,
    """    override suspend fun translate(): TranslationLanguages? {
        val snapshot = try {
""",
    """    override suspend fun translate(): TranslationLanguages? {
        onTranslateStateChanged(Translator.TranslateState.PAGE_SCAN)
        val snapshot = try {
""",
)
replace_once(
    local_ai,
    "        val sourceTranslateLanguage = resolveSourceLanguage(snapshot)\n",
    "        onTranslateStateChanged(Translator.TranslateState.LANGUAGE_DETECTION)\n        val sourceTranslateLanguage = resolveSourceLanguage(snapshot)\n",
)
replace_once(
    local_ai,
    """        return try {
            prepareTranslationModel(
""",
    """        return try {
            onTranslateStateChanged(Translator.TranslateState.MODEL_DOWNLOAD)
            prepareTranslationModel(
""",
)
replace_once(
    local_ai,
    """            val remainingSegments = snapshot.segments.drop(INITIAL_APPLY_SEGMENT_COUNT)
            translateInitialSegments(
""",
    """            val remainingSegments = snapshot.segments.drop(INITIAL_APPLY_SEGMENT_COUNT)
            onTranslateStateChanged(Translator.TranslateState.TRANSLATING)
            translateInitialSegments(
""",
)

screen_state = "app/src/main/kotlin/net/matsudamper/browser/BrowserTabScreenState.kt"
replace_once(
    screen_state,
    "import net.matsudamper.browser.translate.TranslationPriorityLanguage\nimport net.matsudamper.browser.ui.browser.BrowserScreenUiState",
    "import net.matsudamper.browser.translate.TranslationPriorityLanguage\nimport net.matsudamper.browser.translate.Translator\nimport net.matsudamper.browser.ui.browser.BrowserScreenUiState",
)
replace_once(
    screen_state,
    """            TranslationState.Loading,
            TranslationState.Translated,
""",
    """            TranslationState.Loading,
            TranslationState.ScanningPage,
            TranslationState.DetectingLanguage,
            TranslationState.PreparingModel,
            TranslationState.Translating,
            TranslationState.Translated,
""",
)
replace_once(screen_state, "if (translationState == TranslationState.Loading) return", "if (translationState.isInProgress) return")
replace_once(
    screen_state,
    """                PageTranslator(session, pageUrl).translatePage(
                    translationProvider,
                    fromLanguage,
                    toLanguage,
                )
""",
    """                PageTranslator(session, pageUrl).translatePage(
                    translationProvider,
                    fromLanguage,
                    toLanguage,
                ) { translateState ->
                    if (originalPageUrlForRevert == translationStartUrl) {
                        translationState = translateState.toTranslationState()
                    }
                }
""",
)
replace_once(
    screen_state,
    "/** WebApp のピン留めホストと異なるホストへの遷移かどうかを判定する */\ninternal fun isWebAppCrossDomainNavigation",
    """private fun Translator.TranslateState.toTranslationState(): TranslationState = when (this) {
    Translator.TranslateState.PAGE_SCAN -> TranslationState.ScanningPage

    Translator.TranslateState.LANGUAGE_DETECTION -> TranslationState.DetectingLanguage

    Translator.TranslateState.MODEL_DOWNLOAD -> TranslationState.PreparingModel

    Translator.TranslateState.TRANSLATING -> TranslationState.Translating
}

/** WebApp のピン留めホストと異なるホストへの遷移かどうかを判定する */
internal fun isWebAppCrossDomainNavigation""",
)

Path("app/src/test/kotlin/net/matsudamper/browser/TranslationProgressStateTest.kt").write_text(
    """package net.matsudamper.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationProgressStateTest {
    @Test
    fun 翻訳進捗の表示文言を返す() {
        assertEquals("翻訳を開始中...", translationProgressLabel(TranslationState.Loading))
        assertEquals("ページを解析中...", translationProgressLabel(TranslationState.ScanningPage))
        assertEquals("翻訳言語を確認中...", translationProgressLabel(TranslationState.DetectingLanguage))
        assertEquals("ML翻訳モデルを準備中...", translationProgressLabel(TranslationState.PreparingModel))
        assertEquals("翻訳中...", translationProgressLabel(TranslationState.Translating))
    }

    @Test
    fun 翻訳処理中の状態だけ進捗扱いにする() {
        assertTrue(TranslationState.Loading.isInProgress)
        assertTrue(TranslationState.ScanningPage.isInProgress)
        assertTrue(TranslationState.DetectingLanguage.isInProgress)
        assertTrue(TranslationState.PreparingModel.isInProgress)
        assertTrue(TranslationState.Translating.isInProgress)
        assertFalse(TranslationState.Idle.isInProgress)
        assertFalse(TranslationState.Translated.isInProgress)
        assertFalse(TranslationState.Error.isInProgress)
    }
}
"""
)
