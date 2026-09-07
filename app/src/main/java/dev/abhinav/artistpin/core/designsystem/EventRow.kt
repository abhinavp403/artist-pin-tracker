package dev.abhinav.artistpin.core.designsystem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.abhinav.artistpin.core.model.EventSummary
import java.io.File

@Composable
fun EventRow(
    event: EventSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showVenue: Boolean = true,
) {
    ListItem(
        modifier = modifier.clickable(onClick = onClick),
        headlineContent = {
            Text(event.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(
                text = buildString {
                    append(event.date.formatFull())
                    if (showVenue) append(" · ${event.venueName}, ${event.cityName}")
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = { EventThumbnail(event) },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (event.mediaCount > 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            Icons.Default.PhotoLibrary,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = event.mediaCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun EventThumbnail(event: EventSummary) {
    val shape = RoundedCornerShape(8.dp)
    if (event.thumbnailPath != null || event.thumbnailRemotePath != null) {
        val model by rememberThumbnailModel(event.thumbnailPath, event.thumbnailRemotePath)
        AsyncImage(
            model = model,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(48.dp).clip(shape),
        )
    } else {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(shape)
                .padding(0.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceVariant) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = event.date.month.name.take(3),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}
