package net.matsudamper.browser

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import net.matsudamper.browser.data.ThemeMode
import net.matsudamper.browser.ui.common.BrowserTheme
import net.matsudamper.browser.resources.R as ResourcesR

/** アイコン 1 つ分の幅。並び替えの位置計算にも使うため全アイテムで共通にする */
private val ITEM_SIZE = 48.dp

/** アイコン画像自体のサイズ */
private val ICON_SIZE = 24.dp

/** ドラッグ中に端へ近づいたら自動スクロールを始める距離 */
private val AUTO_SCROLL_EDGE = 24.dp

/**
 * タブに対して有効な拡張機能アクションを横スクロールで並べる行。
 * 短押しで拡張機能のポップアップを開き、長押しドラッグで並び替えられる。
 */
@Composable
internal fun ExtensionActionRow(
    actions: List<WebExtensionActionController.ActionUiState>,
    scrollState: ScrollState,
    onActionClick: (String) -> Unit,
    onActionMove: (fromIndex: Int, toIndex: Int) -> Unit,
    onActionMoveEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val itemSizePx = with(density) { ITEM_SIZE.toPx() }
    val autoScrollEdgePx = with(density) { AUTO_SCROLL_EDGE.toPx() }
    val coroutineScope = rememberCoroutineScope()
    val latestActions by rememberUpdatedState(actions)
    val latestOnActionClick by rememberUpdatedState(onActionClick)
    val latestOnActionMove by rememberUpdatedState(onActionMove)
    val latestOnActionMoveEnd by rememberUpdatedState(onActionMoveEnd)
    // 並び替え中のアイテム位置。-1 は並び替えしていない状態
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var viewportWidthPx by remember { mutableIntStateOf(0) }

    fun indexAt(x: Float): Int? {
        val index = (x / itemSizePx).toInt()
        return index.takeIf { it in latestActions.indices }
    }

    MenuWidthNeutralBox(
        modifier = modifier
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .onSizeChanged { viewportWidthPx = it.width },
    ) {
        Row(
            modifier = Modifier
                .testTag(BrowserExtensionActionRowTestTags.Container.testTag)
                .horizontalScroll(scrollState)
                .pointerInput(Unit) {
                    detectTapGestures(
                        // 長押しは並び替え用のため、離しても短押し扱いにしない
                        onLongPress = {},
                        onTap = { offset ->
                            val index = indexAt(offset.x) ?: return@detectTapGestures
                            latestOnActionClick(latestActions[index].extensionId)
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { offset ->
                            draggingIndex = indexAt(offset.x) ?: -1
                            dragOffset = 0f
                        },
                        onDragEnd = {
                            if (draggingIndex >= 0) {
                                latestOnActionMoveEnd()
                            }
                            draggingIndex = -1
                            dragOffset = 0f
                        },
                        onDragCancel = {
                            draggingIndex = -1
                            dragOffset = 0f
                        },
                        onDrag = { change, dragAmount ->
                            if (draggingIndex < 0) return@detectDragGesturesAfterLongPress
                            change.consume()
                            dragOffset += dragAmount.x
                            // 半分を越えたら隣と入れ替える。連続で越えた場合は複数回入れ替える
                            var currentIndex = draggingIndex
                            while (dragOffset > itemSizePx / 2 && currentIndex < latestActions.lastIndex) {
                                latestOnActionMove(currentIndex, currentIndex + 1)
                                dragOffset -= itemSizePx
                                currentIndex += 1
                            }
                            while (dragOffset < -itemSizePx / 2 && currentIndex > 0) {
                                latestOnActionMove(currentIndex, currentIndex - 1)
                                dragOffset += itemSizePx
                                currentIndex -= 1
                            }
                            draggingIndex = currentIndex
                            // 端まで運んだら自動でスクロールし、画面外へも移動できるようにする
                            val itemStart = currentIndex * itemSizePx + dragOffset
                            val visibleStart = scrollState.value.toFloat()
                            val visibleEnd = visibleStart + viewportWidthPx
                            val scrollDelta = when {
                                itemStart < visibleStart + autoScrollEdgePx ->
                                    itemStart - visibleStart - autoScrollEdgePx

                                itemStart + itemSizePx > visibleEnd - autoScrollEdgePx ->
                                    itemStart + itemSizePx - visibleEnd + autoScrollEdgePx

                                else -> 0f
                            }
                            if (scrollDelta != 0f) {
                                coroutineScope.launch {
                                    // スクロールした分だけ指との位置関係がずれるため、オフセットで打ち消す
                                    dragOffset += scrollState.scrollBy(scrollDelta)
                                }
                            }
                        },
                    )
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            actions.forEachIndexed { index, action ->
                val isDragging = index == draggingIndex
                ExtensionActionItem(
                    action = action,
                    modifier = Modifier
                        .zIndex(if (isDragging) 1f else 0f)
                        .graphicsLayer {
                            if (isDragging) {
                                translationX = dragOffset
                                scaleX = DRAG_SCALE
                                scaleY = DRAG_SCALE
                            }
                        },
                )
            }
        }
    }
}

private const val DRAG_SCALE = 1.2f

@Composable
private fun ExtensionActionItem(
    action: WebExtensionActionController.ActionUiState,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(ITEM_SIZE)
            .testTag(BrowserExtensionActionRowTestTags.ActionItem.testTag(action.extensionId))
            .semantics { contentDescription = action.title },
        contentAlignment = Alignment.Center,
    ) {
        val icon = action.icon
        if (icon != null) {
            Image(
                modifier = Modifier.size(ICON_SIZE),
                bitmap = icon.asImageBitmap(),
                contentDescription = null,
            )
        } else {
            Icon(
                modifier = Modifier.size(ICON_SIZE),
                painter = painterResource(ResourcesR.drawable.ic_extension_24dp),
                contentDescription = null,
            )
        }
        val badgeText = action.badgeText
        if (badgeText != null) {
            Text(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 2.dp, bottom = 2.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondary)
                    .padding(horizontal = 4.dp),
                text = badgeText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondary,
                maxLines = 1,
            )
        }
    }
}

/**
 * メニューの幅を押し広げないラッパー。
 * DropdownMenu は Column(width = IntrinsicSize.Max) で幅を決めるため、横スクロールする行を
 * そのまま置くと全アイコン分の幅までメニューが広がってしまう。intrinsic 幅を 0 として扱い、
 * 実測時のみ与えられた幅いっぱいに広げる。
 */
@Composable
private fun MenuWidthNeutralBox(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(
        content = content,
        modifier = modifier,
        measurePolicy = remember {
            object : MeasurePolicy {
                override fun MeasureScope.measure(
                    measurables: List<Measurable>,
                    constraints: Constraints,
                ): MeasureResult {
                    val width = if (constraints.hasBoundedWidth) {
                        constraints.maxWidth
                    } else {
                        constraints.minWidth
                    }
                    val placeables = measurables.map { measurable ->
                        measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
                    }
                    val height = placeables.maxOfOrNull { it.height } ?: 0
                    return layout(width, height) {
                        placeables.forEach { it.place(0, 0) }
                    }
                }

                override fun IntrinsicMeasureScope.minIntrinsicWidth(
                    measurables: List<IntrinsicMeasurable>,
                    height: Int,
                ): Int = 0

                override fun IntrinsicMeasureScope.maxIntrinsicWidth(
                    measurables: List<IntrinsicMeasurable>,
                    height: Int,
                ): Int = 0

                override fun IntrinsicMeasureScope.minIntrinsicHeight(
                    measurables: List<IntrinsicMeasurable>,
                    width: Int,
                ): Int = measurables.maxOfOrNull { it.minIntrinsicHeight(width) } ?: 0

                override fun IntrinsicMeasureScope.maxIntrinsicHeight(
                    measurables: List<IntrinsicMeasurable>,
                    width: Int,
                ): Int = measurables.maxOfOrNull { it.maxIntrinsicHeight(width) } ?: 0
            }
        },
    )
}

@Preview(name = "ExtensionActionRowLight")
@Preview(name = "ExtensionActionRowDark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PreviewExtensionActionRow() {
    BrowserTheme(themeMode = ThemeMode.THEME_SYSTEM) {
        Surface(modifier = Modifier.size(width = 280.dp, height = 64.dp)) {
            ExtensionActionRow(
                actions = List(6) { index ->
                    WebExtensionActionController.ActionUiState(
                        extensionId = "extension_$index",
                        title = "拡張機能 $index",
                        icon = null,
                        badgeText = if (index == 0) "12" else null,
                    )
                },
                scrollState = ScrollState(0),
                onActionClick = {},
                onActionMove = { _, _ -> },
                onActionMoveEnd = {},
            )
        }
    }
}

sealed interface BrowserExtensionActionRowTestTags {
    val id: String
    val testTag get() = "${BrowserExtensionActionRowTestTags::class.java.name}#$id"

    object Container : BrowserExtensionActionRowTestTags { override val id = "extension_action_row" }

    object ActionItem : BrowserExtensionActionRowTestTags {
        override val id = "extension_action_item"

        fun testTag(extensionId: String): String = "$testTag#$extensionId"
    }
}
