package net.matsudamper.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
internal fun ToolbarMenu(
    visibleMenu: Boolean,
    onDismissRequest: () -> Unit,
    onBack: () -> Unit,
    canGoBack: Boolean,
    onRefresh: () -> Unit,
    onHome: () -> Unit,
    onForward: () -> Unit,
    canGoForward: Boolean,
    isPcMode: Boolean,
    onPcModeToggle: () -> Unit,
    showInstallExtensionItem: Boolean,
    onInstallExtension: () -> Unit,
    onTranslatePage: () -> Unit,
    onShare: () -> Unit,
    onFindInPage: () -> Unit,
    onOpenSettings: () -> Unit,
    onAddToHomeScreen: () -> Unit,
    isSimpleView: Boolean,
    onSimpleView: () -> Unit,
    pageZoomPercent: Int,
    onPageZoomIn: () -> Unit,
    onPageZoomOut: () -> Unit,
    onResetPageZoom: () -> Unit,
    onShowTabHistory: () -> Unit,
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
                LongClickIconButton(
                    enabled = canGoBack,
                    onClick = {
                        onDismissRequest()
                        onBack()
                    },
                    onLongClick = {
                        onDismissRequest()
                        onShowTabHistory()
                    },
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_back_24dp),
                        contentDescription = null,
                    )
                }
                Text(
                    text = "戻る",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(
                    onClick = {
                        onDismissRequest()
                        onForward()
                    },
                    enabled = canGoForward,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_forward_24dp),
                        contentDescription = null,
                    )
                }
                Text(
                    text = "進む",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(
                    onClick = {
                        onDismissRequest()
                        onHome()
                    }
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_home_24dp),
                        contentDescription = null,
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
                        onDismissRequest()
                        onRefresh()
                    }
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_refresh_24dp),
                        contentDescription = null,
                    )
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
                text = "ページズーム",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPageZoomOut) {
                    Icon(
                        painter = painterResource(R.drawable.ic_remove_24dp),
                        contentDescription = "縮小",
                    )
                }
                TextButton(
                    onClick = onResetPageZoom,
                    modifier = Modifier.widthIn(min = 64.dp),
                ) {
                    Text(
                        text = "${pageZoomPercent}%",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                IconButton(onClick = onPageZoomIn) {
                    Icon(
                        painter = painterResource(R.drawable.ic_add_24dp),
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
                    painter = painterResource(id = R.drawable.ic_computer_24dp),
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
        DropdownMenuItem(
            text = { Text(text = "シンプル表示") },
            leadingIcon = {
                Icon(
                    painter = painterResource(id = R.drawable.ic_article_24dp),
                    contentDescription = null,
                )
            },
            trailingIcon = {
                Checkbox(
                    checked = isSimpleView,
                    onCheckedChange = null,
                )
            },
            onClick = {
                onDismissRequest()
                onSimpleView()
            },
        )
        if (showInstallExtensionItem) {
            DropdownMenuItem(
                text = {
                    Text(text = "拡張機能をインストール")
                },
                leadingIcon = {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_extension_24dp),
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
                Text(text = "翻訳")
            },
            leadingIcon = {
                Icon(
                    painter = painterResource(id = R.drawable.ic_translate_24dp),
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
                    painter = painterResource(id = R.drawable.ic_share_24dp),
                    contentDescription = null,
                )
            },
            onClick = {
                onDismissRequest()
                onShare()
            },
        )
        DropdownMenuItem(
            text = {
                Text(text = "ホームに追加")
            },
            leadingIcon = {
                Icon(
                    painter = painterResource(id = R.drawable.ic_add_to_home_screen_24dp),
                    contentDescription = null,
                )
            },
            onClick = {
                onDismissRequest()
                onAddToHomeScreen()
            },
        )
        DropdownMenuItem(
            text = {
                Text(text = "ページ内検索")
            },
            leadingIcon = {
                Icon(
                    painter = painterResource(id = R.drawable.ic_search_24dp),
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
                Text(text = "設定")
            },
            leadingIcon = {
                Icon(
                    painter = painterResource(id = R.drawable.ic_settings_24dp),
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

@Composable
private fun LongClickIconButton(
    modifier: Modifier = Modifier,
    enabled: Boolean,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(),
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val shape = IconButtonDefaults.standardShape
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .size(40.dp)
            .clip(shape)
            .background(color = if (enabled) colors.containerColor else colors.disabledContainerColor, shape = shape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                enabled = enabled,
                role = Role.Button,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
            ),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides if (enabled) colors.contentColor else colors.disabledContentColor,
            content = content,
        )
    }
}
