package com.oneasmr.app.data.stats

import com.oneasmr.app.data.local.ListeningSession
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListeningStatsCalculatorTest {
    private val zone: ZoneId = ZoneId.of("UTC")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun session(
        id: String,
        workId: String,
        title: String,
        startedAt: Long,
        durationMs: Long,
    ) = ListeningSession(
        id = id,
        trackKey = "$workId:1",
        workId = workId,
        trackTitle = "$title track",
        workTitle = title,
        startedAt = startedAt,
        endedAt = startedAt + durationMs,
        durationMs = durationMs,
    )

    @Test
    fun `empty history keeps seven zero days and four time buckets`() {
        val report = ListeningStatsCalculator.calculate(
            sessions = emptyList(),
            nowMs = at(2024, 1, 7, 12),
            zone = zone,
        )

        assertEquals(7, report.daily.size)
        assertTrue(report.daily.all { it.durationMs == 0L })
        assertEquals(listOf(0, 6, 12, 18), report.timeBuckets.map { it.startHour })
        assertEquals(0L, report.totalDurationMs)
        assertTrue(report.recent.isEmpty())
    }

    @Test
    fun `top works rank by duration rather than play count`() {
        val start = at(2024, 1, 7, 8)
        val sessions = listOf(
            session("a1", "local:A", "A", start, 60_000L),
            session("a2", "local:A", "A", start + 120_000L, 60_000L),
            session("b1", "local:B", "B", start + 240_000L, 180_000L),
        )

        val report = ListeningStatsCalculator.calculate(sessions, start + 600_000L, zone)

        assertEquals(300_000L, report.totalDurationMs)
        assertEquals(3, report.playCount)
        assertEquals(2, report.workCount)
        assertEquals("local:B", report.topWorks.first().workId)
        assertEquals(180_000L, report.topWorks.first().durationMs)
        assertEquals(1, report.topWorks.first().playCount)
        assertEquals(2, report.topWorks.last().playCount)
        assertEquals(listOf("b1", "a2", "a1"), report.recent.map { it.id })
    }

    @Test
    fun `session crossing midnight splits days and time-of-day buckets`() {
        val session = session(
            id = "overnight",
            workId = "local:NIGHT",
            title = "Night",
            startedAt = at(2024, 1, 1, 22, 30),
            durationMs = 3 * 60 * 60 * 1000L,
        )

        val report = ListeningStatsCalculator.calculate(
            sessions = listOf(session),
            nowMs = at(2024, 1, 2, 12),
            zone = zone,
        )

        assertEquals(2, report.activeDayCount)
        assertEquals(90 * 60 * 1000L, report.daily[5].durationMs)
        assertEquals(90 * 60 * 1000L, report.daily[6].durationMs)
        assertEquals(LocalDate.of(2024, 1, 2), report.daily[6].date)
        assertEquals(90 * 60 * 1000L, report.timeBuckets[0].durationMs)
        assertEquals(90 * 60 * 1000L, report.timeBuckets[3].durationMs)
        assertEquals(0L, report.timeBuckets[1].durationMs)
        assertEquals(0L, report.timeBuckets[2].durationMs)
    }

    @Test
    fun `week totals exclude older history but all-time totals retain it`() {
        val old = session("old", "local:OLD", "Old", at(2023, 12, 1, 20), 60_000L)
        val recent = session("new", "local:NEW", "New", at(2024, 1, 7, 20), 120_000L)

        val report = ListeningStatsCalculator.calculate(
            sessions = listOf(old, recent),
            nowMs = at(2024, 1, 7, 23),
            zone = zone,
        )

        assertEquals(180_000L, report.totalDurationMs)
        assertEquals(120_000L, report.weekDurationMs)
        assertEquals(120_000L, report.todayDurationMs)
        assertEquals(2, report.activeDayCount)
    }

    @Test
    fun `DST overlap always advances and preserves the actual duration`() {
        val newYork = ZoneId.of("America/New_York")
        val start = Instant.parse("2024-11-03T05:30:00Z").toEpochMilli()
        val duration = 2 * 60 * 60 * 1000L
        val overnight = session("dst", "local:DST", "DST", start, duration)

        val report = ListeningStatsCalculator.calculate(
            sessions = listOf(overnight),
            nowMs = start + duration + 60_000L,
            zone = newYork,
        )

        assertEquals(duration, report.totalDurationMs)
        assertEquals(duration, report.weekDurationMs)
        assertEquals(duration, report.timeBuckets.sumOf { it.durationMs })
        assertEquals(1, report.activeDayCount)
    }

    @Test
    fun `zero-duration and malformed intervals do not count as plays`() {
        val start = at(2024, 1, 7, 8)
        val zero = session("zero", "local:ZERO", "Zero", start, 0L)
        val malformed = session("bad", "local:BAD", "Bad", start, 60_000L)
            .copy(endedAt = start - 1L)

        val report = ListeningStatsCalculator.calculate(listOf(zero, malformed), start + 100_000L, zone)

        assertEquals(0, report.playCount)
        assertEquals(0L, report.totalDurationMs)
        assertTrue(report.topWorks.isEmpty())
        assertTrue(report.recent.isEmpty())
    }
}
