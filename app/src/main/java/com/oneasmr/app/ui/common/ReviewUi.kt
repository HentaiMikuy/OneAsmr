package com.oneasmr.app.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.oneasmr.app.data.local.ProgressState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Chinese label for the six listening states (kikoeru semantics, plan Task 15). */
internal fun ProgressState.uiLabel(): String = when (this) {
    ProgressState.none -> "无"
    ProgressState.marked -> "已标记"
    ProgressState.listening -> "收听中"
    ProgressState.listened -> "已听完"
    ProgressState.replay -> "重听中"
    ProgressState.postponed -> "搁置"
}

/**
 * Tappable 1-5 star rating row. [rating] null renders all stars empty
 * ("未评分"); [onRate] receives 1..5 only — the DAO stays the single
 * enforcement point for the range. Colors default to the original
 * tertiary/onSurfaceVariant pairing; callers pass theme roles to re-tint
 * (Wave C detail page: primary filled, outlineVariant empty).
 */
@Composable
internal fun RatingStars(
    rating: Int?,
    onRate: (Int) -> Unit,
    filledColor: Color = MaterialTheme.colorScheme.tertiary,
    emptyColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row {
        for (i in 1..5) {
            val filled = rating != null && i <= rating
            Icon(
                imageVector = if (filled) Icons.Filled.Star else Icons.Outlined.Star,
                contentDescription = "$i 星",
                tint = if (filled) filledColor else emptyColor,
                modifier = Modifier
                    .size(32.dp)
                    .clickable { onRate(i) },
            )
        }
    }
}

/** Compact "yyyy-MM-dd HH:mm" timestamp for the review updatedAt (epoch millis). */
internal fun formatReviewTime(updatedAt: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(updatedAt))
