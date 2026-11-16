package net.matsudamper.browser.translate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import com.google.mlkit.nl.languageid.LanguageIdentification
import net.matsudamper.browser.feature.translatorapi.TranslatorApiEngine

private const val UNDETERMINED_LANGUAGE = "und"

/** ML Kit の言語判定で、候補の言語を確度付きで返す */
internal suspend fun detectLanguagesWithMlKit(
    text: String,
): List<TranslatorApiEngine.DetectedLanguage> = withContext(Dispatchers.IO) {
    LanguageIdentification.getClient().use { identifier ->
        identifier.identifyPossibleLanguages(text).await()
            .filter { it.languageTag != UNDETERMINED_LANGUAGE }
            .map { identified ->
                TranslatorApiEngine.DetectedLanguage(
                    languageTag = identified.languageTag,
                    confidence = identified.confidence.toDouble(),
                )
            }
    }
}
