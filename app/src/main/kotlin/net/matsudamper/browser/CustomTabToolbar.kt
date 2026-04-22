package net.matsudamper.browser

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.matsudamper.browser.resources.R as ResourcesR

internal sealed interface CustomTabToolbarTestTags {
    val id: String
    val testTag get() = "${CustomTabToolbarTestTags::class.java.name}#$id"

    object Toolbar : CustomTabToolbarTestTags { override val id = "custom_tab_toolbar" }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun CustomTabToolbar(
    title: String,
    url: String,
    onClose: () -> Unit,
    onRefresh: () -> Unit,
    onShare: () -> Unit,
    onOpenInBrowser: (() -> Unit)?,
    toolbarColor: Color?,
    showCloseButton: Boolean = true,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val resolvedToolbarColor = toolbarColor ?: MaterialTheme.colorScheme.primaryContainer
    val toolbarContentColor = if (resolvedToolbarColor.luminance() >= 0.5f) {
        Color.Black
    } else {
        Color.White
    }
    val toolbarSecondaryContentColor = toolbarContentColor.copy(alpha = 0.72f)

    Surface(
        color = resolvedToolbarColor,
        contentColor = toolbarContentColor,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(CustomTabToolbarTestTags.Toolbar.testTag),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // ステータスバー領域はSurfaceの背景色で塗りつぶし、コンテンツをその下に押し出す
                .windowInsetsPadding(
                    WindowInsets.statusBarsIgnoringVisibility.only(WindowInsetsSides.Top)
                )
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showCloseButton) {
                IconButton(onClick = onClose) {
                    Icon(
                        painter = painterResource(ResourcesR.drawable.close_24dp),
                        contentDescription = "閉じる",
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = url,
                    style = MaterialTheme.typography.labelSmall,
                    color = toolbarSecondaryContentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        painter = painterResource(ResourcesR.drawable.ic_more_vert_24dp),
                        contentDescription = "メニュー",
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    onOpenInBrowser?.let { openInBrowser ->
                        DropdownMenuItem(
                            text = { Text("ブラウザで開く") },
                            onClick = {
                                menuExpanded = false
                                openInBrowser()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("共有") },
                        onClick = {
                            menuExpanded = false
                            onShare()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("更新") },
                        onClick = {
                            menuExpanded = false
                            onRefresh()
                        },
                    )
                }
            }
        }
    }
}
