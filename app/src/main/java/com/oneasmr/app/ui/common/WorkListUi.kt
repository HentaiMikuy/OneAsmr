package com.oneasmr.app.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oneasmr.app.data.local.ProgressState
import com.oneasmr.app.data.local.ScrapeStatus
import com.oneasmr.app.data.local.WorkListItem
import com.oneasmr.app.data.repository.CoverStore
import com.oneasmr.app.data.repository.CoverType

/**
 * Task 12 list row (Task 8 style preserved): cover thumb + text + badges/actions.
 * Shared verbatim by the library page and the Task 16 dimension-works browse
 * pages (reuse, never duplicate). [showActions] gates the per-row scrape /
 * remove buttons — the library screen owns those flows; browse pages render
 * read-only rows (missing works still show the 已失效 badge).
 */
@Composable
internal fun WorkListRow(
    item: WorkListItem,
    coverStore: CoverStore,
    onClick: () -> Unit,
    onRemove: (WorkListItem) -> Unit = {},
    onScrape: (WorkListItem) -> Unit = {},
    scrapingWorkId: String? = null,
    showActions: Boolean = true,
) {
    val greyed = item.missing
    val contentColor = if (greyed) {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverImage(
            coverStore = coverStore,
            rjCode = item.rjCode,
            type = CoverType.THUMB_240,
            rootFolderUri = item.rootFolderUri,
            relativeDir = item.relativeDir,
            modifier = Modifier
                .size(width = 56.dp, height = 56.dp)
                .clip(RoundedCornerShape(6.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.rjCode,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (item.circleName != null) {
                Text(
                    text = item.circleName,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                item.rateAverage2dp?.let {
                    Text(
                        "★ ${String.format(java.util.Locale.US, "%.2f", it)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                if (item.progress != null && item.progress != ProgressState.none) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        progressLabel(item.progress),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        if (greyed) {
            AssistChip(
                onClick = {},
                label = { Text("已失效", style = MaterialTheme.typography.labelMedium) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            )
            if (showActions) {
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = { onRemove(item) }) { Text("移除") }
            }
        } else if (showActions) {
            ScrapeRowActions(
                item = item,
                scrapingWorkId = scrapingWorkId,
                onScrape = onScrape,
            )
        }
    }
}

/** Task 11 per-row scrape actions: status chip + 刮削/重试/重新刮削 button. */
@Composable
internal fun ScrapeRowActions(
    item: WorkListItem,
    scrapingWorkId: String?,
    onScrape: (WorkListItem) -> Unit,
) {
    val busy = scrapingWorkId == item.id
    val buttonLabel = when (item.scrapeStatus) {
        ScrapeStatus.OK -> "重新刮削"
        ScrapeStatus.FAILED -> "重试"
        ScrapeStatus.NOT_SCRAPED -> "刮削"
    }
    when (item.scrapeStatus) {
        ScrapeStatus.OK -> Unit
        ScrapeStatus.NOT_SCRAPED -> {
            AssistChip(onClick = {}, label = { Text("未刮削", style = MaterialTheme.typography.labelMedium) })
            Spacer(Modifier.width(4.dp))
        }
        ScrapeStatus.FAILED -> {
            AssistChip(
                onClick = {},
                label = { Text("刮削失败", style = MaterialTheme.typography.labelMedium) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            )
            Spacer(Modifier.width(4.dp))
        }
    }
    TextButton(onClick = { onScrape(item) }, enabled = !busy) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        } else {
            Text(buttonLabel)
        }
    }
}

/** Chinese label for the six listening states (aligned with kikoeru semantics). */
internal fun progressLabel(state: ProgressState): String = state.uiLabel()
