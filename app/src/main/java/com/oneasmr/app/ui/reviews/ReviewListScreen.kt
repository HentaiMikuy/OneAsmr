package com.oneasmr.app.ui.reviews

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.oneasmr.app.data.local.ProgressState
import com.oneasmr.app.data.local.ReviewDao
import com.oneasmr.app.data.local.ReviewListItem
import com.oneasmr.app.data.repository.CoverType
import com.oneasmr.app.ui.common.CoverImage
import com.oneasmr.app.ui.common.formatReviewTime
import com.oneasmr.app.ui.common.uiLabel
import com.oneasmr.app.ui.library.rememberCoverStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Task 15 "我标记的作品": every work with a review row, newest update first
 * (kikoeru review-list semantics). Live Room flow — a rating/progress/text
 * change anywhere instantly reorders/refreshes the list.
 */
@HiltViewModel
class ReviewListViewModel @Inject constructor(
    reviewDao: ReviewDao,
) : ViewModel() {
    val reviews: StateFlow<List<ReviewListItem>> =
        reviewDao.getAllJoinedFlow()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

@Composable
fun ReviewListScreen(
    onOpenWork: (String) -> Unit = {},
    viewModel: ReviewListViewModel = hiltViewModel(),
) {
    val reviews by viewModel.reviews.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        Text(
            "我标记的作品",
            style = MaterialTheme.typography.displaySmall,
            maxLines = 1,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        )
        if (reviews.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("还没有标记过作品", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "在作品详情页评分、设置收听进度或写下评语后，作品会出现在这里。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(count = reviews.size, key = { reviews[it].workId }) { index ->
                    ReviewRow(reviews[index], onClick = { onOpenWork(reviews[index].workId) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ReviewRow(item: ReviewListItem, onClick: () -> Unit) {
    val coverStore = rememberCoverStore()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item.rootFolderUri?.let { root ->
            CoverImage(
                coverStore = coverStore,
                rjCode = item.rjCode,
                type = CoverType.THUMB_240,
                rootFolderUri = root,
                relativeDir = item.relativeDir.orEmpty(),
                modifier = Modifier
                    .size(width = 56.dp, height = 56.dp)
                    .clip(MaterialTheme.shapes.small),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.title ?: item.rjCode,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (item.missing) {
                    Spacer(Modifier.width(8.dp))
                    AssistChip(
                        onClick = {},
                        label = { Text("已失效", style = MaterialTheme.typography.labelMedium) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    )
                }
            }
            Text(
                text = item.rjCode,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (item.rating) {
                    null -> Text(
                        "未评分",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(
                            "${item.rating}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
                if (item.progress != ProgressState.none) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        item.progress.uiLabel(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    formatReviewTime(item.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!item.reviewText.isNullOrBlank()) {
                Spacer(Modifier.size(2.dp))
                Text(
                    text = item.reviewText,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
