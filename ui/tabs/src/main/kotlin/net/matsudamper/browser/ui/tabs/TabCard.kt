package net.matsudamper.browser.ui.tabs

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
@Preview
private fun PreviewTabCardShortTitle() {
    TabCard(
        tab = previewTabData(id = "1", title = "Google"),
        selected = false,
        bitmapCache = LruCache(1),
        modifier = Modifier
            .width(160.dp)
            .height(220.dp),
    )
}

@Composable
@Preview
private fun PreviewTabCardLongTitle() {
    TabCard(
        tab = previewTabData(
            id = "2",
            title = "GitHub - matsudamper/browsem: Android Browser App",
        ),
        selected = false,
        bitmapCache = LruCache(1),
        modifier = Modifier
            .width(160.dp)
            .height(220.dp),
    )
}

@Composable
@Preview
private fun PreviewTabCardVeryLongTitle() {
    TabCard(
        tab = previewTabData(
            id = "3",
            title = "非常に長いタイトルで最大フォントでは2行に収まらず縮小が必要になるケースのサンプルテキスト",
        ),
        selected = false,
        bitmapCache = LruCache(1),
        modifier = Modifier
            .width(160.dp)
            .height(220.dp),
    )
}

@Composable
internal fun TabCard(
    tab: TabsScreenTabData,
    selected: Boolean,
    bitmapCache: LruCache<TabPreviewImage, Bitmap>,
    modifier: Modifier = Modifier,
    selectEnabled: Boolean = true,
) {
    Card(
        onClick = tab.listener::onSelect,
        enabled = selectEnabled,
        modifier = modifier,
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (selected) 8.dp else 1.dp
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = if (tab.isPlaying) 8.dp else 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (tab.isPlaying) {
                    Icon(
                        painter = painterResource(R.drawable.ic_music_note),
                        contentDescription = "再生中",
                        modifier = Modifier
                            .size(14.dp)
                            .padding(end = 2.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                BasicText(
                    text = tab.title.ifBlank { "Untitled" },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    autoSize = FitLinesTextAutoSize(
                        minFontSize = 8.sp,
                        maxFontSize = 14.sp,
                        overflowExtraReduction = 4.sp,
                    ),
                    style = MaterialTheme.typography.titleSmall.copy(
                        color = LocalContentColor.current,
                    ),
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = tab.listener::onClose,
                    modifier = Modifier.offset { IntOffset(4, -4) },
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "close",
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 4.dp)
                    .padding(bottom = 4.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                val image = tab.previewImage
                // キャッシュにヒットすれば初期値として即表示する。
                // remember の key を指定しないのは、previewImage が更新されている間も
                // 古い Bitmap を表示し続けてチラつきを避けるため。
                var bitmap: Bitmap? by remember {
                    mutableStateOf(image?.let { bitmapCache.get(it) })
                }
                LaunchedEffect(image) {
                    if (image == null) {
                        bitmap = null
                        return@LaunchedEffect
                    }
                    val cached = bitmapCache.get(image)
                    if (cached != null) {
                        bitmap = cached
                        return@LaunchedEffect
                    }
                    val array = image.bytes
                    val decoded = withContext(Dispatchers.Default) {
                        val options = BitmapFactory.Options().apply { inSampleSize = 2 }
                        BitmapFactory.decodeByteArray(array, 0, array.size, options)
                    }
                    if (decoded != null) {
                        bitmapCache.put(image, decoded)
                        bitmap = decoded
                    }
                }

                val preview = bitmap?.asImageBitmap()
                if (preview != null) {
                    Image(
                        bitmap = preview,
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
        }
    }
}
