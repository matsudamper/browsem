package net.matsudamper.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

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
    tabCount: Int,
    onOpenTabs: () -> Unit,
    onFindInPage: () -> Unit,
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
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = modifier
                    .weight(1f)
                    .onFocusChanged { onFocusChanged(it.hasFocus) }
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(8.dp)
                    .horizontalScroll(rememberScrollState()),
                singleLine = true,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(
                    onGo = { onSubmit(value) }
                )
            )
            IconButton(
                onClick = onOpenTabs,
            ) {
                Text(
                    text = "$tabCount",
                    style = MaterialTheme.typography.titleMedium,
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

@Preview
@Composable
private fun Preview() {
    BrowserToolBar(
        value = "https://google.com",
        onValueChange = {},
        onSubmit = {},
        onFocusChanged = {},
        showInstallExtensionItem = true,
        onInstallExtension = {},
        onOpenSettings = {},
        tabCount = 2,
        onOpenTabs = {},
        onFindInPage = {},
    )
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
