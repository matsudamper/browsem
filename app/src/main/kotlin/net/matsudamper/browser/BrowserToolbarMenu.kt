package net.matsudamper.browser

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import net.matsudamper.browser.resources.R as ResourcesR

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ToolbarMenu(
    visibleMenu: Boolean,
    onDismissRequest: () -> Unit,
    onRefresh: () -> Unit,
    onSuperRefresh: () -> Unit,
    onHome: () -> Unit,
    onForward: () -> Unit,
    canGoForward: Boolean,
    onBack: () -> Unit,
    canGoBack: Boolean,
    onLongPressHistory: () -> Unit,
    isPcMode: Boolean,
    onPcModeToggle: () -> Unit,
    showInstallExtensionItem: Boolean,
    onInstallExtension: () -> Unit,
    onTranslatePage: () -> Unit,
    onShare: () -> Unit,
    onFindInPage: () -> Unit,
    onOpenSettings: () -> Unit,
    onAddToHomeScreen: () -> Unit,
    pageZoomPercent: Int,
    onPageZoomIn: () -> Unit,
    onPageZoomOut: () -> Unit,
    onResetPageZoom: () -> Unit,
    showOpenSettings: Boolean = true,
    showAddToHomeScreen: Boolean = true,
    showHome: Boolean = true,
    onOpenInBrowser: (() -> Unit)? = null,
) {
    DropdownMenu(
        expanded = visibleMenu,
        onDismissRequest = { onDismissRequest() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // 短押しで戻る、長押しでタブ履歴BottomSheetを表示
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .combinedClickable(
                            enabled = canGoBack,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = false),
                            role = Role.Button,
                            onLongClick = {
                                onDismissRequest()
                                onLongPressHistory()
                            },
                            onClick = {
                                onDismissRequest()
                                onBack()
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .padding(12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(ResourcesR.drawable.ic_arrow_back_24dp),
                            contentDescription = null,
                            tint = if (canGoBack) {
                                LocalContentColor.current
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            },
                        )
                    }
                }
                Text(
                    text = "戻る",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // 短押しで進む、長押しでタブ履歴BottomSheetを表示
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .combinedClickable(
                            enabled = canGoForward,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = false),
                            role = Role.Button,
                            onLongClick = {
                                onDismissRequest()
                                onLongPressHistory()
                            },
                            onClick = {
                                onDismissRequest()
                                onForward()
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .padding(12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(ResourcesR.drawable.ic_arrow_forward_24dp),
                            contentDescription = null,
                            tint = if (canGoForward) {
                                LocalContentColor.current
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            },
                        )
                    }
                }
                Text(
                    text = "進む",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (showHome) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(
                        onClick = {
                            onDismissRequest()
                            onHome()
                        }
                    ) {
                        Icon(
                            painter = painterResource(ResourcesR.drawable.ic_home_24dp),
                            contentDescription = null,
                        )
                    }
                    Text(
                        text = "ホーム",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // 短押しで通常更新、長押しでキャッシュをバイパスしてスーパーリフレッシュ
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .testTag(BrowserToolbarMenuTestTags.RefreshButton.testTag)
                        .combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = false),
                            role = Role.Button,
                            onLongClick = {
                                onDismissRequest()
                                onSuperRefresh()
                            },
                            onClick = {
                                onDismissRequest()
                                onRefresh()
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier.padding(12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(ResourcesR.drawable.ic_refresh_24dp),
                            contentDescription = null,
                        )
                    }
                }
                Text(
                    text = "更新",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        HorizontalDivider()
        // ページズームコントロール行（viewport width 操作でテキスト・画像含め全体をズーム）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "ズーム",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .testTag(BrowserToolbarMenuTestTags.ZoomLabel.testTag),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPageZoomOut) {
                    Icon(
                        painter = painterResource(ResourcesR.drawable.ic_remove_24dp),
                        contentDescription = "縮小",
                    )
                }
                TextButton(
                    onClick = onResetPageZoom,
                    modifier = Modifier
                        .widthIn(min = 64.dp)
                        .testTag(BrowserToolbarMenuTestTags.ZoomPercentButton.testTag),
                ) {
                    Text(
                        text = "${pageZoomPercent}%",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                IconButton(onClick = onPageZoomIn) {
                    Icon(
                        painter = painterResource(ResourcesR.drawable.ic_add_24dp),
                        contentDescription = "拡大",
                    )
                }
            }
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(text = "PCページ") },
            leadingIcon = {
                Icon(
                    painter = painterResource(id = ResourcesR.drawable.ic_computer_24dp),
                    contentDescription = null,
                )
            },
            trailingIcon = {
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
                leadingIcon = {
                    Icon(
                        painter = painterResource(id = ResourcesR.drawable.ic_extension_24dp),
                        contentDescription = null,
                    )
                },
                onClick = {
                    onDismissRequest()
                    onInstallExtension()
                },
            )
        }
        DropdownMenuItem(
            text = {
                Text(text = "ページ内検索")
            },
            leadingIcon = {
                Icon(
                    painter = painterResource(id = ResourcesR.drawable.ic_search_24dp),
                    contentDescription = null,
                )
            },
            onClick = {
                onDismissRequest()
                onFindInPage()
            },
        )
        DropdownMenuItem(
            text = {
                Text(text = "翻訳")
            },
            leadingIcon = {
                Icon(
                    painter = painterResource(id = ResourcesR.drawable.ic_translate_24dp),
                    contentDescription = null,
                )
            },
            onClick = {
                onDismissRequest()
                onTranslatePage()
            },
        )
        DropdownMenuItem(
            text = {
                Text(text = "共有")
            },
            leadingIcon = {
                Icon(
                    painter = painterResource(id = ResourcesR.drawable.ic_share_24dp),
                    contentDescription = null,
                )
            },
            onClick = {
                onDismissRequest()
                onShare()
            },
        )
        if (showAddToHomeScreen) {
            DropdownMenuItem(
                text = {
                    Text(text = "ホームに追加")
                },
                leadingIcon = {
                    Icon(
                        painter = painterResource(id = ResourcesR.drawable.ic_add_to_home_screen_24dp),
                        contentDescription = null,
                    )
                },
                onClick = {
                    onDismissRequest()
                    onAddToHomeScreen()
                },
            )
        }
        onOpenInBrowser?.let { openInBrowser ->
            DropdownMenuItem(
                text = {
                    Text(text = "ブラウザで開く")
                },
                leadingIcon = {
                    Icon(
                        painter = painterResource(id = ResourcesR.drawable.ic_open_in_browser_24dp),
                        contentDescription = null,
                    )
                },
                onClick = {
                    onDismissRequest()
                    openInBrowser()
                },
            )
        }
        if (showOpenSettings) {
            DropdownMenuItem(
                text = {
                    Text(text = "設定")
                },
                leadingIcon = {
                    Icon(
                        painter = painterResource(id = ResourcesR.drawable.ic_settings_24dp),
                        contentDescription = null,
                    )
                },
                onClick = {
                    onDismissRequest()
                    onOpenSettings()
                },
            )
        }
    }
}

sealed interface BrowserToolbarMenuTestTags {
    val id: String
    val testTag get() = "${BrowserToolbarMenuTestTags::class.java.name}#$id"

    object ZoomLabel : BrowserToolbarMenuTestTags { override val id = "zoom_label" }
    object ZoomPercentButton : BrowserToolbarMenuTestTags { override val id = "zoom_percent_button" }
    object RefreshButton : BrowserToolbarMenuTestTags { override val id = "refresh_button" }
}
