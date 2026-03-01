package net.matsudamper.browser

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal object TabsLayoutDefaults {
    val minCellWidth: Dp = 220.dp
    val gridPadding: Dp = 12.dp
    val gridSpacing: Dp = 12.dp
    val topBarHeight: Dp = 56.dp
    const val cardAspectRatio: Float = 1f

    fun calculateColumns(availableWidth: Dp): Int {
        return (availableWidth / minCellWidth).toInt().coerceAtLeast(2)
    }

    fun calculateCardWidth(availableWidth: Dp, columns: Int): Dp {
        val spacingWidth = gridSpacing * (columns - 1)
        val contentWidth = availableWidth - (gridPadding * 2) - spacingWidth
        return contentWidth / columns
    }
}

@Composable
internal fun TabsScreen(
    tabs: List<BrowserTab>,
    selectedTabId: Long?,
    onSelectTab: (Long) -> Unit,
    onOpenNewTab: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TabsLayoutDefaults.topBarHeight)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) {
                    Text("戻る")
                }
                Text(
                    text = "Tabs",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onOpenNewTab) {
                    Text("新規")
                }
            }
        }

        if (tabs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text("タブがありません")
            }
            return
        }

        BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
        ) {
            val columns = TabsLayoutDefaults.calculateColumns(maxWidth)
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(TabsLayoutDefaults.gridPadding),
                verticalArrangement = Arrangement.spacedBy(TabsLayoutDefaults.gridSpacing),
                horizontalArrangement = Arrangement.spacedBy(TabsLayoutDefaults.gridSpacing),
            ) {
                items(
                    items = tabs,
                    key = { tab -> tab.id },
                ) { tab ->
                    val selected = tab.id == selectedTabId
                    Card(
                        onClick = { onSelectTab(tab.id) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(TabsLayoutDefaults.cardAspectRatio),
                        colors = CardDefaults.cardColors(
                            containerColor = if (selected) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        ),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                val preview = tab.previewBitmap
                                if (preview != null) {
                                    Image(
                                        bitmap = preview.asImageBitmap(),
                                        contentDescription = "Tab preview",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                } else {
                                    Text(
                                        text = "No Preview",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Tab ${tab.id}",
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = tab.currentUrl,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
