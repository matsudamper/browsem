package net.matsudamper.browser

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDataType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView

@Composable
internal fun UrlTextInput(
    value: String,
    scrollEnabled: Boolean,
    onValueChange: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    textColor: Color,
    enableSuggest: Boolean,
    paddingValues: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val currentValue by rememberUpdatedState(value)
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentOnSubmit by rememberUpdatedState(onSubmit)
    val currentOnFocusChanged by rememberUpdatedState(onFocusChanged)
    val resolvedTextColor by rememberUpdatedState(textColor)
    val resolvedScrollEnabled by rememberUpdatedState(scrollEnabled)
    val resolvedPaddingValues by rememberUpdatedState(paddingValues)

    // 一度AndroidViewを経由しないとBitwardenが認識しない
    AndroidView(
        modifier = modifier,
        factory = { context ->
            ComposeView(context).apply {
                setContent {
                    BasicTextField(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(
                                state = rememberScrollState(),
                                enabled = resolvedScrollEnabled,
                            )
                            .padding(resolvedPaddingValues)
                            .then(
                                if (enableSuggest) {
                                    Modifier.testTag("url_bar")
                                } else {
                                    Modifier
                                }
                            )
                            .onFocusChanged { currentOnFocusChanged(it.hasFocus) }
                            .semantics {
                                if (enableSuggest) {
                                    contentDescription = "Address bar"
                                    contentType = ContentType("url")
                                    contentDataType = ContentDataType.Text
                                }
                            },
                        value = currentValue,
                        onValueChange = { currentOnValueChange(it) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge
                            .merge(
                                color = resolvedTextColor,
                                textAlign = TextAlign.Start,
                            ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions.Default.copy(
                            imeAction = ImeAction.Go,
                            keyboardType = KeyboardType.Uri,
                            autoCorrectEnabled = false,
                        ),
                        keyboardActions = KeyboardActions(
                            onGo = { currentOnSubmit(currentValue) },
                            onDone = { currentOnSubmit(currentValue) },
                            onSearch = { currentOnSubmit(currentValue) },
                        ),
                    )
                }
            }
        },
    )
}
