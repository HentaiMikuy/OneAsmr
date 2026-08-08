package com.oneasmr.app.data.remote

import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Global request-pacing gate shared by ALL scrape sources.
 *
 * Enforces the Task 9 rate-limit contract: NO HTTP request starts within
 * [minRequestIntervalMillis] of the previous request's START. Measured
 * start-to-start (not start-to-end), so a slow request does not buy the next
 * one an early slot. A single [Mutex] serializes [pace] callers, making the
 * spacing GLOBAL across every concurrent worker — not per caller.
 *
 * WHY this is a shared, injectable component and not private to
 * [com.oneasmr.app.data.repository.ScrapeRepository]: an upcoming asmr.one
 * fallback scrape source must count its HTTP requests toward the SAME global
 * 1s interval gate as DLsite requests. Pacing each source independently would
 * double the outbound request rate from this app and defeat the courtesy
 * limit, so Hilt provides ONE singleton (ScrapeModule) that every scrape
 * entry point paces through.
 *
 * JVM-pure and fully testable: all timing reads [clock]; the actual wait is
 * injected via [paceDelay] (defaults to [delay]). Tests inject a fake clock
 * plus an instant delay that advances it — no real-time waits.
 *
 * Dagger note: the @Inject constructor carries production defaults for
 * direct construction only; Dagger cannot bind Kotlin function types from
 * the graph (Function0 wildcard mismatch — repo learning), so ScrapeModule
 * provides the singleton explicitly, mirroring CoverStore in CoverModule.
 */
class RequestPacer @Inject constructor(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val paceDelay: suspend (Long) -> Unit = { delay(it) },
    private val minRequestIntervalMillis: Long = 1_000L,
) {
    private val paceMutex = Mutex()

    // MIN_VALUE / 2 (not MIN_VALUE) so `now - lastRequestStart` cannot
    // overflow on the first pace() — a never-used pacer never delays.
    private var lastRequestStart = Long.MIN_VALUE / 2

    /**
     * Blocks until at least [minRequestIntervalMillis] have passed since the
     * last request START, then stamps the new start. Serialized by
     * [paceMutex]: concurrent callers queue up in arrival order and each one
     * observes the previous caller's stamp, so back-to-back calls are spaced
     * by the full interval.
     */
    suspend fun pace() {
        paceMutex.withLock {
            val now = clock()
            val wait = minRequestIntervalMillis - (now - lastRequestStart)
            if (wait > 0) paceDelay(wait)
            lastRequestStart = clock()
        }
    }

    /**
     * Pretends the last request started exactly one interval ago, so the next
     * [pace] returns immediately. Called before a batch run starts: stalling
     * the queue's FIRST item is pure dead time when no prior request exists
     * to space away from.
     */
    fun prime() {
        lastRequestStart = clock() - minRequestIntervalMillis
    }
}
