package net.matsudamper.browser

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentDataType
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDataType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView

internal sealed interface UrlTextInputTestTags {
    val id: String
    val testTag get() = "${UrlTextInputTestTags::class.java.name}#$id"

    object UrlBar : UrlTextInputTestTags {
        override val id = "url_bar"
    }
}

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
    requestFocusOnShow: Boolean = false,
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
                    val scrollState = rememberScrollState()
                    var textFieldValue by remember { mutableStateOf(TextFieldValue(currentValue)) }
                    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
                    val focusRequester = remember { FocusRequester() }

                    // 表示モードのタップで編集モードに入った直後は、
                    // フィールドへ直接タッチされていないため自前でフォーカスを要求する。
                    LaunchedEffect(Unit) {
                        if (requestFocusOnShow) {
                            runCatching { focusRequester.requestFocus() }
                        }
                    }

                    // 外部から値が変更された場合にテキストを反映（カーソルは末尾へ）
                    LaunchedEffect(currentValue) {
                        if (textFieldValue.text != currentValue) {
                            textFieldValue = TextFieldValue(
                                text = currentValue,
                                selection = TextRange(currentValue.length),
                            )
                        }
                    }

                    // カーソルが画面外に出た場合に追従してスクロール
                    LaunchedEffect(textFieldValue.selection, textLayoutResult) {
                        if (!resolvedScrollEnabled) return@LaunchedEffect
                        val layout = textLayoutResult ?: return@LaunchedEffect
                        if (textFieldValue.text.isEmpty()) return@LaunchedEffect
                        // 音声入力ではテキストと選択範囲が連続して更新され、直前の短いテキスト用の
                        // TextLayoutResult が一時的に残ることがある。getCursorRect はレイアウト対象を
                        // 超える offset を受け付けないため、現在値だけでなくレイアウト済み文字数でも制限する。
                        val offset = cursorOffsetForLayout(
                            selectionEnd = textFieldValue.selection.end,
                            textLength = textFieldValue.text.length,
                            layoutTextLength = layout.layoutInput.text.length,
                        )
                        val cursorRect = layout.getCursorRect(offset)
                        // viewportWidth = コンテンツ幅 - 最大スクロール量
                        val viewportWidth = layout.size.width - scrollState.maxValue
                        val currentScroll = scrollState.value
                        val targetScroll = when {
                            cursorRect.right.toInt() > currentScroll + viewportWidth ->
                                (cursorRect.right.toInt() - viewportWidth).coerceAtLeast(0)
                            cursorRect.left.toInt() < currentScroll ->
                                cursorRect.left.toInt().coerceAtLeast(0)
                            else -> return@LaunchedEffect
                        }
                        scrollState.scrollTo(targetScroll)
                    }

                    BasicTextField(
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .horizontalScroll(
                                state = scrollState,
                                enabled = resolvedScrollEnabled,
                            )
                            .padding(resolvedPaddingValues)
                            .then(
                                if (enableSuggest) {
                                    Modifier.testTag(UrlTextInputTestTags.UrlBar.testTag)
                                } else {
                                    Modifier
                                },
                            )
                            .onFocusChanged { currentOnFocusChanged(it.hasFocus) }
                            .semantics {
                                if (enableSuggest) {
                                    contentDescription = "Address bar"
                                    contentType = ContentType("url")
                                    contentDataType = ContentDataType.Text
                                }
                            },
                        value = textFieldValue,
                        onValueChange = { newValue ->
                            val previousText = textFieldValue.text
                            textFieldValue = newValue
                            // selection だけの更新で親 state を上書きすると、
                            // フォーカス直後の空文字化が古い URL で戻されてしまう。
                            if (newValue.text != previousText) {
                                currentOnValueChange(newValue.text)
                            }
                        },
                        onTextLayout = { textLayoutResult = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge
                            .merge(
                                color = resolvedTextColor,
                                textAlign = TextAlign.Start,
                            ),
                        cursorBrush = SolidColor(lightColorScheme().primary),
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

internal fun cursorOffsetForLayout(
    selectionEnd: Int,
    textLength: Int,
    layoutTextLength: Int,
): Int = selectionEnd.coerceIn(0, minOf(textLength, layoutTextLength))

/**
 * 非フォーカス時の URL バー表示。
 *
 * 編集機能（[UrlTextInput]）とは完全に分離し、現在ページの URL を 1 行で表示するだけにする。
 * タップで編集モードへ遷移し、ロングプレスで URL コピーなどの任意アクションを実行する。
 *
 * テストは UrlBar ノードの Text セマンティクスから表示中の URL を読むため、
 * [enableSuggest] が true のときのみ testTag を付与する。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun UrlDisplay(
    value: String,
    textColor: Color,
    enableSuggest: Boolean,
    paddingValues: PaddingValues,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .then(
                if (enableSuggest) {
                    Modifier.testTag(UrlTextInputTestTags.UrlBar.testTag)
                } else {
                    Modifier
                },
            )
            // 子 BasicText の Text セマンティクスを UrlBar ノードへ集約する。
            // テストは UrlBar ノードの Text から表示中の URL を読むため必須。
            .semantics(mergeDescendants = true) {
                if (enableSuggest) {
                    contentDescription = "Address bar"
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicText(
            modifier = Modifier
                .fillMaxWidth()
                .padding(paddingValues),
            text = value,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyLarge.merge(
                color = textColor,
                textAlign = TextAlign.Start,
            ),
        )
    }
}
