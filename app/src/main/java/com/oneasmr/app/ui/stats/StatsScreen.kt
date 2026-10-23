package com.oneasmr.app.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oneasmr.app.data.local.ListeningSession
import com.oneasmr.app.data.stats.DailyListening
import com.oneasmr.app.data.stats.ListeningStatsReport
import com.oneasmr.app.data.stats.TimeBucketListening
import com.oneasmr.app.data.stats.WorkListening
import com.oneasmr.app.navigation.LocalBottomClusterHeight
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** 收听统计根标签页：所有图表均由 Compose 基础布局绘制，不依赖图表库。 */
@Composable
fun StatsScreen(
    viewModel: StatsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val bottomClusterHeight = LocalBottomClusterHeight.current

    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("收听统计", style = MaterialTheme.typography.displaySmall)
            Text(
                "播放后自动记录，统计数据仅保存在本机",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        when {
            !state.loaded -> StatsLoadingState(
                bottomClusterHeight = bottomClusterHeight,
                modifier = Modifier.weight(1f),
            )

            state.report.playCount <= 0 -> StatsEmptyState(
                bottomClusterHeight = bottomClusterHeight,
                modifier = Modifier.weight(1f),
            )

            else -> StatsContent(
                report = state.report,
                bottomClusterHeight = bottomClusterHeight,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StatsLoadingState(
    bottomClusterHeight: Dp,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(bottom = bottomClusterHeight)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(
            "正在加载收听统计…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatsEmptyState(
    bottomClusterHeight: Dp,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            // 底部悬浮簇是覆盖层，先避让再将空态内容居中。
            .padding(bottom = bottomClusterHeight)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        androidx.compose.material3.Icon(
            Icons.Outlined.BarChart,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text("还没有收听记录", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "开始播放作品或单档后，这里会自动统计收听时长、播放次数和常听内容。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun StatsContent(
    report: ListeningStatsReport,
    bottomClusterHeight: Dp,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp, bottom = bottomClusterHeight + 16.dp),
    ) {
        StatsSummaryCards(report)
        Spacer(Modifier.height(20.dp))
        DailyTrendCard(
            daily = report.daily.takeLast(7),
            weekDurationMs = report.weekDurationMs,
        )
        Spacer(Modifier.height(16.dp))
        TimeBucketsCard(report.timeBuckets)
        Spacer(Modifier.height(16.dp))
        TopWorksCard(report.topWorks.take(5))
        Spacer(Modifier.height(16.dp))
        RecentListeningCard(report.recent.take(5))
        Spacer(Modifier.height(16.dp))
        Text(
            "统计口径：仅计算本机实际播放，暂停和缓冲不计时长；播放次数按连续播放区间计数，" +
                "单曲循环不重复计次。历史从本功能启用后开始记录。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatsSummaryCards(report: ListeningStatsReport) {
    Column {
        Text("有记录以来", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SummaryCard(
                label = "总收听时长",
                value = formatListeningDuration(report.totalDurationMs),
                detail = "今天 ${formatListeningDuration(report.todayDurationMs)}",
                modifier = Modifier.weight(1f),
            )
            SummaryCard(
                label = "播放次数",
                value = "${report.playCount.coerceAtLeast(0)} 次",
                detail = "最近 7 天 ${formatListeningDuration(report.weekDurationMs)}",
                modifier = Modifier.weight(1f),
            )
            SummaryCard(
                label = "收听作品数",
                value = "${report.workCount.coerceAtLeast(0)} 部",
                detail = "活跃 ${report.activeDayCount.coerceAtLeast(0)} 天",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SummaryCard(
    label: String,
    value: String,
    detail: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DailyTrendCard(
    daily: List<DailyListening>,
    weekDurationMs: Long,
) {
    StatsCard(
        title = "最近 7 天",
        subtitle = "每日收听趋势 · 共 ${formatListeningDuration(weekDurationMs)}",
    ) {
        if (daily.isEmpty()) {
            StatsSectionEmpty("暂无每日收听记录")
        } else {
            val maxDurationMs = daily.maxOf { it.durationMs.coerceAtLeast(0L) }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                daily.forEachIndexed { index, day ->
                    val durationMs = day.durationMs.coerceAtLeast(0L)
                    val fraction = durationFraction(durationMs, maxDurationMs)
                    val barHeight = if (durationMs > 0L) {
                        (112.dp * fraction).coerceAtLeast(4.dp)
                    } else {
                        2.dp
                    }
                    val isToday = index == daily.lastIndex

                    Column(
                        Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            formatDailyDuration(durationMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            minLines = 2,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(6.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(112.dp),
                            contentAlignment = Alignment.BottomCenter,
                        ) {
                            Box(
                                Modifier
                                    .width(22.dp)
                                    .height(barHeight)
                                    .clip(
                                        RoundedCornerShape(
                                            topStart = 6.dp,
                                            topEnd = 6.dp,
                                        ),
                                    )
                                    .background(
                                        when {
                                            durationMs <= 0L -> MaterialTheme.colorScheme.surfaceVariant
                                            isToday -> MaterialTheme.colorScheme.primary
                                            else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                                        },
                                    ),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (isToday) "今天" else chineseWeekday(day.date),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isToday) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                        )
                        Text(
                            "${day.date.monthValue}/${day.date.dayOfMonth}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TimeBucketsCard(buckets: List<TimeBucketListening>) {
    // 固定展示四个时段，即使某项缺失或为零也保留该行。
    val normalizedBuckets = listOf(0, 6, 12, 18).map { startHour ->
        TimeBucketListening(
            startHour = startHour,
            durationMs = buckets.firstOrNull { it.startHour == startHour }
                ?.durationMs
                ?.coerceAtLeast(0L)
                ?: 0L,
        )
    }
    val totalDurationMs = normalizedBuckets.sumOf { it.durationMs }

    StatsCard(
        title = "收听时段",
        subtitle = "各时段占有记录以来收听时长的比例",
    ) {
        normalizedBuckets.forEachIndexed { index, bucket ->
            val fraction = durationFraction(bucket.durationMs, totalDurationMs)
            val percentage = (fraction * 100f).roundToInt()

            if (index > 0) Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${bucket.startHour}–${bucket.startHour + 6} 时",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "${formatListeningDuration(bucket.durationMs)} · $percentage%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                if (fraction > 0f) {
                    Box(
                        Modifier
                            .fillMaxWidth(fraction)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
    }
}

@Composable
private fun TopWorksCard(works: List<WorkListening>) {
    StatsCard(
        title = "常听作品",
        subtitle = "按实际播放时长排序 · 前 5 名",
    ) {
        if (works.isEmpty()) {
            StatsSectionEmpty("暂无作品排行")
        } else {
            works.forEachIndexed { index, work ->
                if (index > 0) HorizontalDivider()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        modifier = Modifier.size(28.dp),
                        shape = CircleShape,
                        color = if (index == 0) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("${index + 1}", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            work.title.ifBlank { "未命名作品" },
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${formatListeningDuration(work.durationMs)} · 播放 ${work.playCount.coerceAtLeast(0)} 次",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentListeningCard(recent: List<ListeningSession>) {
    StatsCard(
        title = "最近收听",
        subtitle = "最近 5 条记录 · 本地时间",
    ) {
        if (recent.isEmpty()) {
            StatsSectionEmpty("暂无最近收听记录")
        } else {
            recent.forEachIndexed { index, session ->
                if (index > 0) HorizontalDivider()
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                ) {
                    Text(
                        session.workTitle.ifBlank { "未知作品" },
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        session.trackTitle.ifBlank { "未命名音轨" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${formatRecentTime(session.startedAt)} · 收听 ${formatListeningDuration(session.durationMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatsCard(
    title: String,
    subtitle: String? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            subtitle?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
            content()
        }
    }
}

@Composable
private fun StatsSectionEmpty(message: String) {
    Text(
        message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        textAlign = TextAlign.Center,
    )
}

/** 长时长省略秒，分钟和秒级记录保留更直观的细分。 */
private fun formatListeningDuration(durationMs: Long): String {
    if (durationMs <= 0L) return "0秒"
    val totalSeconds = durationMs / 1_000L
    if (totalSeconds == 0L) return "不足1秒"

    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return when {
        hours > 0L && minutes > 0L -> "${hours}小时${minutes}分钟"
        hours > 0L -> "${hours}小时"
        minutes > 0L && seconds > 0L -> "${minutes}分钟${seconds}秒"
        minutes > 0L -> "${minutes}分钟"
        else -> "${seconds}秒"
    }
}

/** 每日柱顶使用更短的中文单位，避免七列在窄屏上拥挤。 */
private fun formatDailyDuration(durationMs: Long): String {
    if (durationMs <= 0L) return "0秒"
    val totalSeconds = durationMs / 1_000L
    if (totalSeconds == 0L) return "不足1秒"
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    return when {
        hours > 0L && minutes > 0L -> "${hours}时${minutes}分"
        hours > 0L -> "${hours}时"
        minutes > 0L -> "${minutes}分"
        else -> "${totalSeconds % 60L}秒"
    }
}

private fun durationFraction(durationMs: Long, totalDurationMs: Long): Float {
    if (durationMs <= 0L || totalDurationMs <= 0L) return 0f
    return (durationMs.toDouble() / totalDurationMs.toDouble())
        .toFloat()
        .coerceIn(0f, 1f)
}

private fun chineseWeekday(date: LocalDate): String = when (date.dayOfWeek.value) {
    1 -> "周一"
    2 -> "周二"
    3 -> "周三"
    4 -> "周四"
    5 -> "周五"
    6 -> "周六"
    else -> "周日"
}

private fun formatRecentTime(timestampMs: Long): String {
    val zone = ZoneId.systemDefault()
    val dateTime = Instant.ofEpochMilli(timestampMs).atZone(zone)
    val date = dateTime.toLocalDate()
    val today = LocalDate.now(zone)
    return when (date) {
        today -> "今天 ${TIME_FORMATTER.format(dateTime)}"
        today.minusDays(1) -> "昨天 ${TIME_FORMATTER.format(dateTime)}"
        else -> if (date.year == today.year) {
            DATE_TIME_FORMATTER.format(dateTime)
        } else {
            FULL_DATE_TIME_FORMATTER.format(dateTime)
        }
    }
}

private val TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)
private val DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.CHINA)
private val FULL_DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm", Locale.CHINA)
