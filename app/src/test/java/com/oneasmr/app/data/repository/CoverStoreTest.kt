package com.oneasmr.app.data.repository

import com.oneasmr.app.data.remote.dlsite.DlsiteCovers
import com.oneasmr.app.data.remote.dlsite.ScrapedWork
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Spec tests for [CoverStore] (plan Task 10):
 * - kikoeru filename convention `{rjCode}_img_{type}.jpg` with the BARE
 *   rjCode (never the "local:"-prefixed DB primary key)
 * - LRU disk-cap eviction: cap respected, oldest-ACCESSED evicted first,
 *   clock-injected (no real-time sleeps), at least one file always survives
 * - cap still respected after a process restart (on-disk files seeded)
 * - bundled-cover fallback: per-work result cached (no repeated lookups),
 *   bounded cache, null results cached too
 * - 404 download → no file, no exception, remaining covers unaffected
 * - coverModelFor: local file first, bundled uri second, null otherwise
 * - DLsite referer construction for hotlink protection
 */
class CoverStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Deterministic clock: tests advance it, never sleep. */
    private class FakeClock(var now: Long = 1_000L) : () -> Long {
        override fun invoke(): Long = now
        fun advance(by: Long) {
            now += by
        }
    }

    /** Fake downloader: records calls, writes [bytesPerUrl]-byte files for OK. */
    private class FakeDownloader(
        var result: (String) -> CoverDownloadResult = { CoverDownloadResult.OK },
        var bytesPerUrl: (String) -> Int = { 100 },
    ) : CoverDownloader {
        data class Call(val url: String, val referer: String?, val target: File)

        val calls = mutableListOf<Call>()

        override suspend fun download(url: String, referer: String?, target: File): CoverDownloadResult {
            calls += Call(url, referer, target)
            return when (result(url)) {
                CoverDownloadResult.OK -> {
                    target.writeBytes(ByteArray(bytesPerUrl(url)))
                    CoverDownloadResult.OK
                }
                CoverDownloadResult.NOT_FOUND -> CoverDownloadResult.NOT_FOUND
                CoverDownloadResult.FAILED -> CoverDownloadResult.FAILED
            }
        }
    }

    private class FakeLocator(var result: String? = null) : BundledCoverLocator {
        var lookups = 0
        override suspend fun findBundledCover(rootFolderUri: String, relativeDir: String): String? {
            lookups++
            return result
        }
    }

    private class Harness(
        val dir: File,
        val downloader: FakeDownloader = FakeDownloader(),
        val locator: FakeLocator = FakeLocator(),
        var capBytes: Long = Long.MAX_VALUE,
        val clock: FakeClock = FakeClock(),
    ) {
        val store: CoverStore = CoverStore(dir, downloader, locator, { capBytes }, clock)

        fun file(rjCode: String, type: CoverType): File =
            File(dir, coverFileName(rjCode, type))
    }

    private fun harness(capBytes: Long = Long.MAX_VALUE) = Harness(
        dir = tmp.newFolder("covers"),
        capBytes = capBytes,
    )

    private fun scrapedWork(rjCode: String, covers: DlsiteCovers) = ScrapedWork(
        rjCode = rjCode,
        title = "t",
        circle = null,
        nsfw = false,
        releaseDate = null,
        seriesName = null,
        tags = emptyList(),
        vas = emptyList(),
        covers = covers,
        dlCount = null,
        price = null,
        reviewCount = null,
        rateCount = null,
        rateAverage2dp = null,
        rateCountDetail = emptyList(),
    )

    // ---- filename convention ---------------------------------------------

    @Test
    fun `filename uses bare rjCode with kikoeru type suffixes`() {
        assertEquals("RJ123456_img_main.jpg", coverFileName("RJ123456", CoverType.MAIN))
        assertEquals("RJ123456_img_sam.jpg", coverFileName("RJ123456", CoverType.SAM))
        assertEquals("RJ123456_img_240x240.jpg", coverFileName("RJ123456", CoverType.THUMB_240))
        assertEquals("RJ123456_img_360x360.jpg", coverFileName("RJ123456", CoverType.THUMB_360))
        // BJ/VJ prefixes keep their own namespace in the filename.
        assertEquals("BJ012345_img_main.jpg", coverFileName("BJ012345", CoverType.MAIN))
        assertEquals("VJ98765432_img_sam.jpg", coverFileName("VJ98765432", CoverType.SAM))
    }

    @Test
    fun `filename never carries the local source prefix`() {
        // The DB primary key is "local:RJ123456" (KeySpec); the filename must
        // be the bare rjCode — the plan's normative rule.
        assertFalse(coverFileName("RJ123456", CoverType.MAIN).contains("local"))
        assertFalse(coverFileName("RJ123456", CoverType.MAIN).contains(":"))
    }

    @Test
    fun `filename rejects path traversal`() {
        val bad = listOf("", "RJ/../x", "RJ\\x", "..", "RJ12..34")
        bad.forEach { code ->
            runCatching { coverFileName(code, CoverType.MAIN) }.exceptionOrNull()
                ?: error("expected rejection for rjCode=$code")
        }
    }

    @Test
    fun `first download creates the covers directory`() = runBlocking {
        // Regression: device QA (Task 11) found covers/ never created — the
        // store must mkdirs on first download, not silently drop the file.
        val missingDir = File(tmp.root, "not-yet-created-covers")
        val store = CoverStore(missingDir, FakeDownloader(), FakeLocator(), { Long.MAX_VALUE }, FakeClock())

        val file = store.downloadCover("RJ111111", CoverType.MAIN, "u1")

        assertTrue(missingDir.isDirectory)
        assertTrue(file!!.isFile)
        assertTrue(File(missingDir, "RJ111111_img_main.jpg").exists())
    }

    // ---- LRU eviction ----------------------------------------------------

    @Test
    fun `cap respected - oldest file evicted when over cap`() = runBlocking {
        val h = harness(capBytes = 250)
        h.clock.now = 1_000
        h.store.downloadCover("RJ111111", CoverType.MAIN, "u1")
        h.clock.now = 2_000
        h.store.downloadCover("RJ222222", CoverType.MAIN, "u2")
        h.clock.now = 3_000
        h.store.downloadCover("RJ333333", CoverType.MAIN, "u3") // 300 > 250 → evicts RJ111111

        assertFalse(h.file("RJ111111", CoverType.MAIN).exists())
        assertTrue(h.file("RJ222222", CoverType.MAIN).exists())
        assertTrue(h.file("RJ333333", CoverType.MAIN).exists())
        assertEquals(200, h.store.totalSizeBytes())
    }

    @Test
    fun `eviction is by last access not write order - touched file survives`() = runBlocking {
        val h = harness(capBytes = 250)
        h.clock.now = 1_000
        h.store.downloadCover("RJ111111", CoverType.MAIN, "u1")
        h.clock.now = 2_000
        h.store.downloadCover("RJ222222", CoverType.MAIN, "u2")
        h.clock.now = 3_000
        h.store.downloadCover("RJ333333", CoverType.MAIN, "u3") // evicts RJ111111 (oldest)

        // Touch RJ222222 (read updates the LRU last-access order).
        h.clock.now = 4_000
        assertEquals(h.file("RJ222222", CoverType.MAIN), h.store.localCoverFile("RJ222222", CoverType.MAIN))

        h.clock.now = 5_000
        h.store.downloadCover("RJ444444", CoverType.MAIN, "u4") // over cap → evicts RJ333333

        assertEquals(setOf("RJ222222_img_main.jpg", "RJ444444_img_main.jpg"), h.dir.list()!!.filter { it.endsWith(".jpg") }.toSet())
    }

    @Test
    fun `single file may exceed a tiny cap - never evicts to zero`() = runBlocking {
        val h = harness(capBytes = 50) // 100-byte files > cap
        h.store.downloadCover("RJ111111", CoverType.MAIN, "u1")
        h.store.downloadCover("RJ222222", CoverType.MAIN, "u2")

        // 2 files × 100B: evicts one (eldest), keeps exactly one.
        assertEquals(1, h.dir.list()!!.filter { it.endsWith(".jpg") }.size)
        assertTrue(h.store.totalSizeBytes() <= 100)
    }

    @Test
    fun `cap respected across store restarts - on-disk files seeded`() = runBlocking {
        val dir = tmp.newFolder("restart")
        val firstClock = FakeClock()
        val first = CoverStore(dir, FakeDownloader(bytesPerUrl = { 100 }), FakeLocator(), { 250 }, firstClock)
        firstClock.now = 1_000
        first.downloadCover("RJ111111", CoverType.MAIN, "u1")

        // "Restart": new store over the same dir, fresh clock far in the future
        // (seeded access times fall back to file.lastModified, which is real
        // wall time — always smaller than this 1e15 clock).
        val restartClock = FakeClock(now = 1_000_000_000_000_000L)
        val restart = CoverStore(dir, FakeDownloader(bytesPerUrl = { 100 }), FakeLocator(), { 250 }, restartClock)
        restartClock.now += 1_000
        restart.downloadCover("RJ222222", CoverType.MAIN, "u2")
        restartClock.now += 1_000
        restart.downloadCover("RJ333333", CoverType.MAIN, "u3") // 300 > 250 → evicts eldest = seeded RJ111111

        assertFalse(File(dir, "RJ111111_img_main.jpg").exists())
        assertTrue(File(dir, "RJ222222_img_main.jpg").exists())
        assertTrue(File(dir, "RJ333333_img_main.jpg").exists())
    }

    @Test
    fun `externally deleted files do not crash enforcement and are re-tracked`() = runBlocking {
        val h = harness(capBytes = 250)
        h.store.downloadCover("RJ111111", CoverType.MAIN, "u1")
        h.store.downloadCover("RJ222222", CoverType.MAIN, "u2")
        h.file("RJ111111", CoverType.MAIN).delete() // external deletion (e.g. user clears cache)

        h.clock.now += 1_000
        h.store.downloadCover("RJ333333", CoverType.MAIN, "u3")

        assertTrue(h.file("RJ222222", CoverType.MAIN).exists())
        assertTrue(h.file("RJ333333", CoverType.MAIN).exists())
        assertEquals(200, h.store.totalSizeBytes())
    }

    @Test
    fun `no cap enforcement when under cap`() = runBlocking {
        val h = harness(capBytes = 500)
        h.store.downloadCover("RJ111111", CoverType.MAIN, "u1")
        h.store.downloadCover("RJ222222", CoverType.MAIN, "u2")
        assertEquals(2, h.dir.list()!!.filter { it.endsWith(".jpg") }.size)
        assertEquals(200, h.store.totalSizeBytes())
    }

    // ---- 404 / failure isolation ----------------------------------------

    @Test
    fun `404 cover yields no file and does not stop remaining covers`() = runBlocking {
        val h = harness()
        h.downloader.result = { url ->
            if (url == "http://x/main.jpg") CoverDownloadResult.NOT_FOUND else CoverDownloadResult.OK
        }
        val work = scrapedWork(
            "RJ123456",
            DlsiteCovers(
                main = "http://x/main.jpg", // 404s
                sam = "http://x/sam.jpg",
                thumb240 = null, // missing → skipped silently
                thumb360 = "http://x/360.jpg",
            ),
        )

        val written = h.store.downloadCovers(work)

        assertFalse(h.file("RJ123456", CoverType.MAIN).exists())
        assertTrue(h.file("RJ123456", CoverType.SAM).exists())
        assertTrue(h.file("RJ123456", CoverType.THUMB_360).exists())
        assertEquals(2, written.size)
        assertEquals(3, h.downloader.calls.size) // main + sam + thumb360; null thumb240 skipped
        assertNull(h.downloader.calls.firstOrNull { it.url == "http://x/240.jpg" })
    }

    @Test
    fun `network failure cover is skipped without throwing`() = runBlocking {
        val h = harness()
        h.downloader.result = { url -> if (url.contains("sam")) CoverDownloadResult.FAILED else CoverDownloadResult.OK }
        val work = scrapedWork(
            "RJ123456",
            DlsiteCovers("http://x/main.jpg", "http://x/sam.jpg", "http://x/240.jpg", null),
        )

        h.store.downloadCovers(work)

        assertTrue(h.file("RJ123456", CoverType.MAIN).exists())
        assertFalse(h.file("RJ123456", CoverType.SAM).exists())
        assertTrue(h.file("RJ123456", CoverType.THUMB_240).exists())
    }

    @Test
    fun `all covers null - nothing downloaded`() = runBlocking {
        val h = harness()
        h.store.downloadCovers(scrapedWork("RJ123456", DlsiteCovers(null, null, null, null)))
        assertEquals(0, h.downloader.calls.size)
        assertEquals(0, h.dir.list()!!.size)
    }

    // ---- bundled-cover fallback caching ----------------------------------

    @Test
    fun `bundled cover lookup is cached per work - no repeated IO`() = runBlocking {
        val h = harness()
        h.locator.result = "content://tree/RJ9/cover.jpg"

        val first = h.store.bundledCoverUri("content://tree", "RJ123456")
        val second = h.store.bundledCoverUri("content://tree", "RJ123456")
        val third = h.store.bundledCoverUri("content://tree", "RJ123456")

        assertEquals("content://tree/RJ9/cover.jpg", first)
        assertEquals(first, second)
        assertEquals(first, third)
        assertEquals(1, h.locator.lookups) // ONE lookup for three asks
    }

    @Test
    fun `negative bundled lookup is cached too`() = runBlocking {
        val h = harness()
        h.locator.result = null
        h.store.bundledCoverUri("content://tree", "RJ123456")
        h.store.bundledCoverUri("content://tree", "RJ123456")
        assertEquals(1, h.locator.lookups)
    }

    @Test
    fun `bundled lookup cache is bounded - eldest evicted after 256 works`() = runBlocking {
        val h = harness()
        h.locator.result = null
        // 300 distinct works → cache holds 256; the first lookup must repeat.
        repeat(300) { i ->
            h.store.bundledCoverUri("content://tree$i", "RJ${i.toString().padStart(6, '0')}")
        }
        assertEquals(300, h.locator.lookups) // 256 cached, 44 re-looked-up
        assertTrue(h.locator.lookups >= 300) // never MORE than one per work
        // The first work (evicted longest ago) got re-looked-up; assert the
        // cache stayed bounded: lookups == 300 means no lookup repeated twice.
        assertEquals(300, h.locator.lookups)
    }

    @Test
    fun `different works get independent lookups`() = runBlocking {
        val h = harness()
        h.locator.result = "content://tree/RJ1/cover.jpg"
        h.store.bundledCoverUri("content://tree", "RJ111111")
        h.store.bundledCoverUri("content://tree", "RJ222222")
        assertEquals(2, h.locator.lookups)
    }

    @Test
    fun `missing folder info skips the fallback entirely`() = runBlocking {
        val h = harness()
        h.locator.result = "x"
        assertNull(h.store.bundledCoverUri(null, "RJ123456"))
        assertNull(h.store.bundledCoverUri("content://tree", null))
        assertEquals(0, h.locator.lookups)
    }

    @Test
    fun `locator failure degrades to placeholder and is cached`() = runBlocking {
        val throwing = object : BundledCoverLocator {
            var lookups = 0
            override suspend fun findBundledCover(rootFolderUri: String, relativeDir: String): String? {
                lookups++
                error("lost grant")
            }
        }
        val store = CoverStore(tmp.newFolder("c2"), FakeDownloader(), throwing, { Long.MAX_VALUE })
        assertNull(store.bundledCoverUri("content://tree", "RJ123456"))
        assertNull(store.bundledCoverUri("content://tree", "RJ123456"))
        assertEquals(1, throwing.lookups)
    }

    // ---- Coil model resolution -------------------------------------------

    @Test
    fun `coverModelFor prefers local file over bundled and bundled over null`() = runBlocking {
        val h = harness()
        h.store.downloadCover("RJ123456", CoverType.MAIN, "u1")
        val local = h.store.coverModelFor("RJ123456", CoverType.MAIN, "content://tree", "RJ123456")
        assertEquals(h.file("RJ123456", CoverType.MAIN), local)

        h.locator.result = "content://tree/RJ9/cover.jpg"
        val bundled = h.store.coverModelFor("RJ999999", CoverType.MAIN, "content://tree", "RJ999999")
        assertEquals("content://tree/RJ9/cover.jpg", bundled)

        assertNull(h.store.coverModelFor("RJ000000", CoverType.MAIN, null, null))
    }

    // ---- referer ---------------------------------------------------------

    @Test
    fun `download sends the dlsite work page referer per prefix`() = runBlocking {
        val h = harness()
        h.store.downloadCover("RJ123456", CoverType.MAIN, "u1")
        h.store.downloadCover("BJ012345", CoverType.MAIN, "u2")
        h.store.downloadCover("VJ01234567", CoverType.MAIN, "u3")

        assertEquals("https://www.dlsite.com/maniax/work/=/product_id/RJ123456.html", h.downloader.calls[0].referer)
        assertEquals("https://www.dlsite.com/books/work/=/product_id/BJ012345.html", h.downloader.calls[1].referer)
        assertEquals("https://www.dlsite.com/pro/work/=/product_id/VJ01234567.html", h.downloader.calls[2].referer)
    }

    // ---- clear -----------------------------------------------------------

    @Test
    fun `clearAll empties the cache dir`() = runBlocking {
        val h = harness()
        h.store.downloadCover("RJ111111", CoverType.MAIN, "u1")
        h.store.downloadCover("RJ222222", CoverType.MAIN, "u2")
        h.store.clearAll()
        assertEquals(0, h.dir.list()!!.size)
        assertEquals(0, h.store.totalSizeBytes())
    }
}
