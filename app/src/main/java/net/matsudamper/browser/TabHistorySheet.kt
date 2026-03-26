package net.matsudamper.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.matsudamper.browser.TabHistoryEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TabHistorySheet(
    historyItems: List<TabHistoryEntry>,
    currentIndex: Int,
    onNavigateTo: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val listState = rememberLazyListState()

    // 現在のエントリ付近までスクロールする
    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0 && historyItems.isNotEmpty()) {
            listState.scrollToItem(currentIndex)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Text(
            text = "このタブの履歴",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
        ) {
            // 新しい順（最後のエントリが上）で表示する
            val reversedItems = historyItems.reversed()
            val reversedCurrentIndex = if (currentIndex >= 0) historyItems.lastIndex - currentIndex else -1
            itemsIndexed(reversedItems) { reversedIdx, entry ->
                val originalIndex = historyItems.lastIndex - reversedIdx
                val isCurrent = reversedIdx == reversedCurrentIndex
                ListItem(
                    headlineContent = {
                        Text(
                            text = entry.title.ifBlank { entry.url },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                    supportingContent = {
                        if (entry.title.isNotBlank()) {
                            Text(
                                text = entry.url,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    },
                    colors = if (isCurrent) {
                        ListItemDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        )
                    } else {
                        ListItemDefaults.colors()
                    },
                    modifier = Modifier.clickable { onNavigateTo(originalIndex) },
                )
            }
            // BottomSheet の下部マージン
            item {
                Box(modifier = Modifier.padding(bottom = 16.dp))
            }
        }
    }
}
