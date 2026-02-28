package net.matsudamper.browser

import android.net.Uri
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction

@Composable
internal fun BrowserUrlTextField(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
    onFocusChanged: (Boolean) -> Unit = {},
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { onFocusChanged(it.hasFocus) },
        singleLine = true,
        keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(
            onGo = { onSubmit(normalizeUrl(value)) }
        )
    )
}

internal fun normalizeUrl(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isBlank()) {
        return "https://google.com"
    }

    if (trimmed.startsWith("g ")) {
        return "https://www.google.com/search?q=${Uri.encode(trimmed.removePrefix("g "))}"
    }
    if (trimmed.startsWith("d ")) {
        return "https://duckduckgo.com/?q=${Uri.encode(trimmed.removePrefix("d "))}"
    }
    if (trimmed.startsWith("w ")) {
        return "https://ja.wikipedia.org/wiki/Special:Search?search=${Uri.encode(trimmed.removePrefix("w "))}"
    }

    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        return trimmed
    }

    if (trimmed.startsWith("localhost") || trimmed.matches(Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+(:\\d+)?$"))) {
        return "http://$trimmed"
    }

    if (trimmed.contains(' ')) {
        return "https://www.google.com/search?q=${Uri.encode(trimmed)}"
    }

    return if (trimmed.contains('.')) {
        "https://$trimmed"
    } else {
        "https://www.google.com/search?q=${Uri.encode(trimmed)}"
    }
}
