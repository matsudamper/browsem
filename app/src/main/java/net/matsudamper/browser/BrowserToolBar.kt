package net.matsudamper.browser

import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
internal fun BrowserToolBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
    onFocusChanged: (Boolean) -> Unit,
    showInstallExtensionItem: Boolean,
    onInstallExtension: () -> Unit,
    onOpenSettings: () -> Unit,
    onShare: () -> Unit,
    tabCount: Int,
    onOpenTabs: () -> Unit,
    onRefresh: () -> Unit,
    onHome: () -> Unit,
    onForward: () -> Unit,
    canGoForward: Boolean,
    onFindInPage: () -> Unit,
    isPcMode: Boolean,
    onPcModeToggle: () -> Unit,
) {
    val latestOnOpenTabs by rememberUpdatedState(onOpenTabs)
    val swipeToOpenTabsModifier = modifier.pointerInput(Unit) {
        detectDownSwipe(
            density = this,
            onDownSwipe = {
                latestOnOpenTabs()
            }
        )
    }

    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = swipeToOpenTabsModifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AndroidView(
                factory = { context ->
                    EditText(context).apply {
                        id = R.id.url_bar
                        setSingleLine(true)
                        imeOptions = EditorInfo.IME_ACTION_GO
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                        hint = "Search or type URL"
                        contentDescription = "Address bar"
                        isFocusable = true
                        isFocusableInTouchMode = true
                        isClickable = true
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                        addTextChangedListener(
                            object : TextWatcher {
                                override fun beforeTextChanged(
                                    s: CharSequence?,
                                    start: Int,
                                    count: Int,
                                    after: Int
                                ) {
                                }

                                override fun onTextChanged(
                                    s: CharSequence?,
                                    start: Int,
                                    before: Int,
                                    count: Int
                                ) {
                                    onValueChange(s?.toString().orEmpty())
                                }

                                override fun afterTextChanged(s: Editable?) {
                                }
                            }
                        )
                        setOnFocusChangeListener { _, hasFocus ->
                            onFocusChanged(hasFocus)
                        }
                        setOnEditorActionListener { _, actionId, event ->
                            val isSubmitAction = actionId == EditorInfo.IME_ACTION_GO ||
                                actionId == EditorInfo.IME_ACTION_DONE ||
                                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                            if (isSubmitAction) {
                                onSubmit(text?.toString().orEmpty())
                                true
                            } else {
                                false
                            }
                        }
                    }
                },
                update = { editText ->
                    if (editText.text.toString() != value) {
                        editText.setText(value)
                        editText.setSelection(editText.text.length)
                    }
                },
                modifier = modifier
                    .weight(1f)
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
            IconButton(
                onClick = onOpenTabs,
            ) {
                Text(
                    text = "$tabCount",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outline,
                            shape = RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            var visibleMenu by remember { mutableStateOf(false) }
            IconButton(
                onClick = { visibleMenu = !visibleMenu }
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_more_vert_24dp),
                    contentDescription = "Menu"
                )
                DropdownMenu(
                    expanded = visibleMenu,
                    onDismissRequest = {
                        visibleMenu = false
                    }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            IconButton(
                                onClick = {
                                    visibleMenu = false
                                    onRefresh()
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "更新",
                                )
                            }
                            Text(
                                text = "更新",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            IconButton(
                                onClick = {
                                    visibleMenu = false
                                    onHome()
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Home,
                                    contentDescription = "ホーム",
                                )
                            }
                            Text(
                                text = "ホーム",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            IconButton(
                                onClick = {
                                    visibleMenu = false
                                    onForward()
                                },
                                enabled = canGoForward,
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = "進む",
                                )
                            }
                            Text(
                                text = "進む",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(text = "PCページ") },
                        leadingIcon = {
                            Checkbox(
                                checked = isPcMode,
                                onCheckedChange = null,
                            )
                        },
                        onClick = { onPcModeToggle() },
                    )
                    if (showInstallExtensionItem) {
                        DropdownMenuItem(
                            text = {
                                Text(text = "拡張機能をインストール")
                            },
                            onClick = {
                                visibleMenu = false
                                onInstallExtension()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = {
                            Text(text = "共有")
                        },
                        onClick = {
                            visibleMenu = false
                            onShare()
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(text = "ページ内検索")
                        },
                        onClick = {
                            visibleMenu = false
                            onFindInPage()
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(text = "設定")
                        },
                        onClick = {
                            visibleMenu = false
                            onOpenSettings()
                        },
                    )
                }
            }
        }
    }
}

@Preview(name = "Light")
@Preview(name = "Dark", uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun Preview() {
    BrowserTheme(themeMode = net.matsudamper.browser.data.ThemeMode.THEME_SYSTEM) {
        BrowserToolBar(
            value = "https://google.com",
            onValueChange = {},
            onSubmit = {},
            onFocusChanged = {},
            showInstallExtensionItem = true,
            onInstallExtension = {},
            onOpenSettings = {},
            onShare = {},
            tabCount = 2,
            onOpenTabs = {},
            isPcMode = false,
            onPcModeToggle = {},
            onFindInPage = {},
            onRefresh = {},
            onHome = {},
            onForward = {},
            canGoForward = false,
        )
    }
}

private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectDownSwipe(
    density: Density,
    onDownSwipe: () -> Unit,
) {
    val triggerDistance = with(density) { 56.dp.toPx() }
    var totalDrag = 0f
    detectVerticalDragGestures(
        onDragStart = {
            totalDrag = 0f
        },
        onVerticalDrag = { _, dragAmount ->
            if (dragAmount > 0f) {
                totalDrag += dragAmount
            } else {
                totalDrag = 0f
            }
        },
        onDragEnd = {
            if (totalDrag >= triggerDistance) {
                onDownSwipe()
            }
            totalDrag = 0f
        },
        onDragCancel = {
            totalDrag = 0f
        },
    )
}
