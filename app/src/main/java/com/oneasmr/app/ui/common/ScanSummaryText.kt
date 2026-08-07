package com.oneasmr.app.ui.common

import com.oneasmr.app.data.scanner.RescanSummary

/**
 * Pure formatting of the completion-summary counters (Task 8) — unit-tested
 * without any Android dependency. "失效" is the missing-work count.
 */
fun formatScanSummary(summary: RescanSummary): String {
    val base = "新增 ${summary.added} · 更新 ${summary.updated} · 失效 ${summary.missing}"
    return if (summary.warnings.isEmpty()) base else "$base · 警告 ${summary.warnings.size}"
}
