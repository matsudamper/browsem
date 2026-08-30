package net.matsudamper.browser.ui.settings.form

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import net.matsudamper.browser.resources.R as ResourcesR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InputListScreenScaffold(
    pageTitle: String,
    pageSubTitle: String,
    listTitle: String,
    onClickBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(pageTitle) },
                navigationIcon = {
                    IconButton(onClick = { onClickBack() }) {
                        Icon(
                            painter = painterResource(ResourcesR.drawable.ic_arrow_back_24dp),
                            contentDescription = "戻る",
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier.padding(
                top = paddingValues.calculateTopPadding(),
            )
        ) {
            val horizontalPadding = PaddingValues(
                start = paddingValues.calculateStartPadding(LocalLayoutDirection.current),
                end = paddingValues.calculateEndPadding(LocalLayoutDirection.current),
            )
            val itemHorizontalPadding = 16.dp
            Text(
                modifier = Modifier
                    .padding(horizontalPadding)
                    .padding(horizontal = itemHorizontalPadding, vertical = 8.dp),
                text = pageSubTitle,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                modifier = Modifier
                    .padding(horizontalPadding)
                    .padding(itemHorizontalPadding),
                text = listTitle,
            )
            val containerShape = MaterialTheme.shapes.extraLarge
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontalPadding),
                shape = containerShape.copy(
                    bottomEnd = CornerSize(0.dp),
                    bottomStart = CornerSize(0.dp)
                ),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                val paddingValues = PaddingValues(
                    top = with(LocalDensity.current) {
                        containerShape.topStart.toPx(Size.Unspecified, LocalDensity.current)
                            .coerceAtLeast(containerShape.topStart.toPx(Size.Unspecified, LocalDensity.current))
                            .toDp()
                    },
                    bottom = paddingValues.calculateBottomPadding(),
                )
                content(paddingValues)
            }
        }
    }
}
