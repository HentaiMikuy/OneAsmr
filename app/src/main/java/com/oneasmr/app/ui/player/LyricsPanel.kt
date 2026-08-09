package com.oneasmr.app.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.oneasmr.app.domain.lyrics.LrcLyrics

/**
 * Synced lyrics panel (plan Task 20): the active line (last line whose
 * timestamp <= playback position) is highlighted in the primary color and
 * auto-scrolled into the viewport center; tapping a line seeks the player to
 * that timestamp.
 *
 * The container carries a `lyrics activeLine=N` contentDescription — the
 * uiautomator-visible, time-ordered assertion channel for device QA (the
 * active index must advance as playback position advances, ±200ms).
 */
@Composable
fun LyricsPanel(
    lyrics: LrcLyrics,
    activeIndex: Int,
    onLineClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0 && activeIndex < lyrics.lines.size) {
            listState.animateScrollToItem(activeIndex)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .height(280.dp)
            .semantics { contentDescription = "lyrics activeLine=$activeIndex" },
        // contentPadding 而非外层 padding：留白随内容滚动，不影响活动行居中。
        contentPadding = PaddingValues(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        itemsIndexed(lyrics.lines) { index, line ->
            val active = index == activeIndex
            Text(
                text = line.text.ifEmpty { "♪" },
                style = if (active) {
                    MaterialTheme.typography.titleMedium
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                fontWeight = if (active) FontWeight.Bold else null,
                color = if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onLineClick(line.timestampMs) }
                    .padding(vertical = 10.dp),
            )
        }
    }
}
