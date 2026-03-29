package net.matsudamper.browser.ui.browser

import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import net.matsudamper.browser.ReadabilityArticle

sealed interface SimpleViewScreenTestTags {
    val id: String
    val testTag get() = "${SimpleViewScreenTestTags::class.java.name}#$id"

    object SimpleView : SimpleViewScreenTestTags { override val id = "simple_view" }
}

@Composable
fun SimpleViewScreen(
    article: ReadabilityArticle,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag(SimpleViewScreenTestTags.SimpleView.testTag),
    ) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = false
                    settings.cacheMode = WebSettings.LOAD_NO_CACHE
                    loadDataWithBaseURL(
                        null,
                        buildArticleHtml(article),
                        "text/html",
                        "UTF-8",
                        null,
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.close_24dp),
                contentDescription = "シンプル表示を閉じる",
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}

private fun buildArticleHtml(article: ReadabilityArticle): String {
    val escapedTitle = article.title
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    val escapedByline = article.byline
        ?.replace("&", "&amp;")?.replace("<", "&lt;")?.replace(">", "&gt;")
    return buildString {
        append("<!DOCTYPE html><html><head>")
        append("<meta charset=\"UTF-8\">")
        append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">")
        append("<style>")
        append("body{font-family:sans-serif;max-width:720px;margin:0 auto;padding:1em 1.2em;line-height:1.8;color:#222;background:#fafafa}")
        append("h1{font-size:1.6em;line-height:1.3;margin-bottom:0.4em}")
        append("h2,h3{line-height:1.3}")
        append("img{max-width:100%;height:auto}")
        append("pre{overflow-x:auto;background:#eee;padding:0.8em;border-radius:4px}")
        append("a{color:#0066cc}")
        append(".byline{color:#666;font-size:0.9em;margin-top:0}")
        append("</style>")
        append("</head><body>")
        append("<h1>$escapedTitle</h1>")
        if (escapedByline != null) {
            append("<p class=\"byline\">$escapedByline</p>")
        }
        append(article.content)
        append("</body></html>")
    }
}
