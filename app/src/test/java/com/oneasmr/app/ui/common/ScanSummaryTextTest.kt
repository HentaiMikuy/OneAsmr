package com.oneasmr.app.ui.common

import com.oneasmr.app.data.scanner.RescanSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class ScanSummaryTextTest {

    @Test
    fun `formats the three counters without warnings`() {
        assertEquals(
            "新增 2 · 更新 1 · 失效 3",
            formatScanSummary(RescanSummary(2, 1, 3, 9, emptyList())),
        )
    }

    @Test
    fun `appends the warning count when present`() {
        assertEquals(
            "新增 0 · 更新 0 · 失效 0 · 警告 2",
            formatScanSummary(RescanSummary(0, 0, 0, 0, listOf("a", "b"))),
        )
    }
}
