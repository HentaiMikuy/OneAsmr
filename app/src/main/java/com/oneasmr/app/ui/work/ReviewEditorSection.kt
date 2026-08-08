package com.oneasmr.app.ui.work

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oneasmr.app.data.local.ProgressState
import com.oneasmr.app.ui.common.RatingStars
import com.oneasmr.app.ui.common.formatReviewTime
import com.oneasmr.app.ui.common.uiLabel

/**
 * Task 15 review section: 1-5 star rating (DAO-validated, single enforcement
 * point), six-state progress chips (kikoeru semantics — values never
 * renamed), free-form review text with an explicit save, and a 清除标记
 * delete-review action. Rendered BOTH on the healthy detail page and inside
 * the invalid/missing-work state, so a review of a missing work stays
 * viewable and clearable (plan failure path).
 */
@Composable
internal fun ReviewEditorSection(
    state: WorkDetailUiState,
    onRating: (Int?) -> Unit,
    onProgress: (ProgressState) -> Unit,
    onTextChange: (String) -> Unit,
    onSaveText: () -> Unit,
    onClear: () -> Unit,
) {
    val review = state.review
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("评分与进度", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (review != null) {
                    TextButton(onClick = onClear) { Text("清除标记") }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                RatingStars(
                    rating = review?.rating,
                    onRate = onRating,
                    filledColor = MaterialTheme.colorScheme.primary,
                    emptyColor = MaterialTheme.colorScheme.outlineVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    when (review?.rating) {
                        null -> "未评分"
                        else -> "${review.rating} 星"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (review?.rating != null) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { onRating(null) }) { Text("清除评分") }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ProgressState.entries.forEach { p ->
                    val selected = (review?.progress ?: ProgressState.none) == p
                    Surface(
                        onClick = { onProgress(p) },
                        shape = CircleShape,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                    ) {
                        Text(
                            p.uiLabel(),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.reviewText,
                onValueChange = onTextChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("评语") },
                minLines = 2,
                maxLines = 5,
                shape = MaterialTheme.shapes.small,
            )
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (review != null) {
                    Text(
                        "更新于 ${formatReviewTime(review.updatedAt)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                TextButton(onClick = onSaveText) { Text("保存评语") }
            }
        }
    }
}
