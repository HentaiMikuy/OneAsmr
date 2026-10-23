package com.oneasmr.app.data.stats

import com.oneasmr.app.data.local.ListeningSession
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class ListeningStatsReport(
    val totalDurationMs: Long = 0L,
    val weekDurationMs: Long = 0L,
    val todayDurationMs: Long = 0L,
    val playCount: Int = 0,
    val workCount: Int = 0,
    val activeDayCount: Int = 0,
    val daily: List<DailyListening> = emptyList(),
    val timeBuckets: List<TimeBucketListening> = emptyList(),
    val topWorks: List<WorkListening> = emptyList(),
    val recent: List<ListeningSession> = emptyList(),
) {
    companion object {
        val EMPTY = ListeningStatsReport()
    }
}

data class DailyListening(
    val date: LocalDate,
    val durationMs: Long,
)

data class TimeBucketListening(
    val startHour: Int,
    val durationMs: Long,
)

data class WorkListening(
    val workId: String,
    val title: String,
    val durationMs: Long,
    val playCount: Int,
)

/**
 * Pure aggregation for the local statistics page. Continuous sessions are
 * split at local hour boundaries, so midnight-spanning playback contributes to
 * the correct days and time-of-day buckets instead of being assigned wholesale
 * to its start time. The daily chart always covers today and the prior 6 days.
 */
object ListeningStatsCalculator {
    fun calculate(
        sessions: List<ListeningSession>,
        nowMs: Long,
        zone: ZoneId,
    ): ListeningStatsReport {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val weekStart = today.minusDays(6)
        val dailyDurations = LongArray(7)
        val bucketDurations = LongArray(4)
        val activeDays = mutableSetOf<LocalDate>()
        val works = mutableMapOf<String, WorkAccumulator>()
        val validSessions = sessions.filter { it.durationMs > 0L && it.endedAt > it.startedAt }
        var totalDurationMs = 0L

        validSessions.forEach { session ->
            val work = works.getOrPut(session.workId) {
                WorkAccumulator(
                    workId = session.workId,
                    title = session.workTitle.ifBlank { session.trackTitle },
                    latestStartedAt = session.startedAt,
                )
            }
            if (session.startedAt >= work.latestStartedAt) {
                work.title = session.workTitle.ifBlank { session.trackTitle }
                work.latestStartedAt = session.startedAt
            }
            work.playCount += 1

            var cursor = session.startedAt
            while (cursor < session.endedAt) {
                val localTime = Instant.ofEpochMilli(cursor).atZone(zone)
                // Advance on the Instant timeline. ZonedDateTime.plusHours uses
                // local-time rules and can resolve unexpectedly across a DST
                // overlap; the defensive fallback also guarantees progress.
                val hourStart = localTime.truncatedTo(ChronoUnit.HOURS).toInstant()
                val nextHour = hourStart.plus(1, ChronoUnit.HOURS).toEpochMilli()
                val segmentEnd = if (nextHour > cursor) {
                    minOf(session.endedAt, nextHour)
                } else {
                    minOf(session.endedAt, cursor + 3_600_000L)
                }
                val segmentDuration = segmentEnd - cursor
                val date = localTime.toLocalDate()

                totalDurationMs += segmentDuration
                work.durationMs += segmentDuration
                activeDays += date
                bucketDurations[localTime.hour / 6] += segmentDuration
                if (!date.isBefore(weekStart) && !date.isAfter(today)) {
                    val index = ChronoUnit.DAYS.between(weekStart, date).toInt()
                    dailyDurations[index] += segmentDuration
                }
                cursor = segmentEnd
            }
        }

        val daily = (0L..6L).map { offset ->
            val date = weekStart.plusDays(offset)
            DailyListening(date, dailyDurations[offset.toInt()])
        }
        val topWorks = works.values
            .sortedWith(
                compareByDescending<WorkAccumulator> { it.durationMs }
                    .thenByDescending { it.playCount }
                    .thenBy { it.title },
            )
            .take(5)
            .map { WorkListening(it.workId, it.title, it.durationMs, it.playCount) }

        return ListeningStatsReport(
            totalDurationMs = totalDurationMs,
            weekDurationMs = dailyDurations.sum(),
            todayDurationMs = dailyDurations.last(),
            playCount = validSessions.size,
            workCount = works.size,
            activeDayCount = activeDays.size,
            daily = daily,
            timeBuckets = listOf(0, 6, 12, 18).mapIndexed { index, hour ->
                TimeBucketListening(hour, bucketDurations[index])
            },
            topWorks = topWorks,
            recent = validSessions.sortedByDescending { it.startedAt }.take(5),
        )
    }

    private data class WorkAccumulator(
        val workId: String,
        var title: String,
        var latestStartedAt: Long,
        var durationMs: Long = 0L,
        var playCount: Int = 0,
    )
}
