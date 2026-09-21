package net.matsudamper.browser.ui.browser

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview

/** 表示対象のタブが解決されるまでのプレースホルダ。 */
@Composable
fun BrowserContentLoadingIndicator(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
@Preview(widthDp = 320, heightDp = 240)
private fun PreviewBrowserContentLoadingIndicator() {
    MaterialTheme {
        BrowserContentLoadingIndicator()
    }
}
