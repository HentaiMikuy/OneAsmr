package com.oneasmr.app.data.repository

import com.oneasmr.app.data.remote.dlsite.ScrapedWork
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The four cover sizes DLsite serves; the directory-name part of the kikoeru
 * filename convention `{rjCode}_img_{type}.jpg` (kikoeru filesystem/utils.js).
 */
enum class CoverType(val dirName: String) {
    MAIN("main"),
    SAM("sam"),
    THUMB_240("240x240"),
    THUMB_360("360x360"),
}

/**
 * Cover download + local cache (plan Task 10).
 *
 * Downloads scraped covers into an app-private `covers/` directory with the
 * kikoeru filename convention `{rjCode}_img_{type}.jpg` — the filename uses
 * the BARE rjCode ("RJ123456"), NEVER the DB primary key ("local:RJ123456"):
 * the key spec (data/local/KeySpec.kt) forbids source prefixes in file names,
 * and kikoeru's own covers dir uses bare ids.
 *
 * Responsibilities:
 * - [downloadCovers]: persist every non-null cover URL of a [ScrapedWork].
 *   Per-cover failures are ISOLATED: a 404 (or any failure) leaves no file,
 *   never throws, and does not stop the remaining covers — the scrape flow
 *   (Task 11) is unaffected, and the UI falls back to the placeholder.
 * - [localCoverFile]: cached file for (rjCode, type), updating the LRU
 *   last-access timestamp.
 * - [bundledCoverUri]: pre-scrape fallback — cover files bundled inside the
 *   work folder (cover.jpg / folder.jpg / 封面*). Results are cached per work
 *   in a BOUNDED LRU map so list scrolling never repeats SAF IO per frame.
 * - Disk-cache cap: default 500MB from [com.oneasmr.app.data.local.settings.SettingsStore]
 *   (wired by CoverModule). The cap applies to THIS store's own `covers/`
 *   directory — Coil's internal memory/disk caches do not manage externally
 *   written files. Eviction is LRU by last access time.
 *
 * Threading: the whole public surface is serialized on a [Mutex]. Batch
 * scraping (Task 11) is serial anyway, and the single lock guarantees no two
 * downloads race on the same target file or the LRU bookkeeping.
 *
 * JVM-pure by construction: [coversDir], [downloader], [bundledLocator],
 * [cacheCapBytes] and [clock] are injected — unit tests use a temp dir, fake
 * downloader/locator, a fixed cap and a fake clock (no real-time sleeps; the
 * repo flake convention).
 *
 * @param cacheCapBytes supplies the current cap in BYTES; production reads a
 *   StateFlow derived from SettingsStore.cacheSizeCapMb, tests pass a mutable
 *   holder.
 * @param clock epoch millis supplier; production is System::currentTimeMillis,
 *   tests advance a fake clock to drive LRU order deterministically.
 */
class CoverStore(
    private val coversDir: File,
    private val downloader: CoverDownloader,
    private val bundledLocator: BundledCoverLocator,
    private val cacheCapBytes: () -> Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val lock = Mutex()

    /**
     * Per-work bundled-cover lookup cache: bounded LRU, caches negative
     * results too (no repeated SAF listing during list scrolling).
     */
    private val bundledCache = BoundedLruCache<String, String?>()

    /**
     * LRU bookkeeping keyed by absolute file path. accessOrder=true: iteration
     * order == last-access order (eldest first), which is exactly the eviction
     * order. Access time is ALSO stored explicitly so tests can assert it and
     * so the seed path (files found on disk after a process restart) is
     * uniform.
     */
    private data class Entry(val file: File, var accessTime: Long, var sizeBytes: Long)

    private val entries = HashMap<String, Entry>()

    // ---- downloads -------------------------------------------------------

    /**
     * Downloads every non-null cover of [work] into covers/. Each cover is
     * independent: failures (404, network) are logged-and-skipped, the others
     * still land. Returns the files written (empty when nothing succeeded).
     */
    suspend fun downloadCovers(work: ScrapedWork): List<File> {
        val c = work.covers
        val results = mutableListOf<File>()
        downloadOne(work.rjCode, CoverType.MAIN, c.main, work.rjCode)?.let { results += it }
        downloadOne(work.rjCode, CoverType.SAM, c.sam, work.rjCode)?.let { results += it }
        downloadOne(work.rjCode, CoverType.THUMB_240, c.thumb240, work.rjCode)?.let { results += it }
        downloadOne(work.rjCode, CoverType.THUMB_360, c.thumb360, work.rjCode)?.let { results += it }
        return results
    }

    /**
     * Downloads one cover. Returns the cached [File] on success, or null when
     * the URL is missing / 404 / network-failed. NEVER throws — callers treat
     * null as "placeholder".
     */
    suspend fun downloadCover(rjCode: String, type: CoverType, url: String): File? =
        downloadOne(rjCode, type, url, rjCode)

    private suspend fun downloadOne(rjCode: String, type: CoverType, url: String?, workCode: String): File? {
        if (url.isNullOrBlank()) return null
        return lock.withLock {
            // First download creates the covers dir (device QA found the dir
            // never existing: Task 10 covered filenames/LRU but not mkdirs).
            if (!coversDir.isDirectory && !coversDir.mkdirs()) return null
            val target = File(coversDir, coverFileName(rjCode, type))
            val tmp = File(coversDir, target.name + PART_SUFFIX)
            val result = downloader.download(url, dlsiteRefererFor(workCode), tmp)
            when (result) {
                CoverDownloadResult.OK -> {
                    if (tmp.renameTo(target)) {
                        val now = clock()
                        // Read the size from target: tmp is gone after the rename.
                        entries[target.path] = Entry(target, now, target.length())
                        enforceCapLocked()
                        target
                    } else {
                        tmp.delete()
                        null // rename failed (external interference) — treat as failed
                    }
                }
                CoverDownloadResult.NOT_FOUND, CoverDownloadResult.FAILED -> {
                    tmp.delete()
                    null
                }
            }
        }
    }

    // ---- reads -----------------------------------------------------------

    /**
     * Cached local file for (rjCode, type), or null. Touches the LRU access
     * time on hit (Coil displays the file from disk; the store cannot observe
     * Coil's reads, so "access" = the app asking for the cover — the explicit
     * last-access tracking the plan requires).
     */
    suspend fun localCoverFile(rjCode: String, type: CoverType): File? = lock.withLock {
        val file = File(coversDir, coverFileName(rjCode, type))
        if (!file.isFile) return null
        entries[file.path]?.accessTime = clock()
        file
    }

    /** True when a downloaded cover exists on disk. */
    suspend fun hasLocalCover(rjCode: String, type: CoverType): Boolean = lock.withLock {
        File(coversDir, coverFileName(rjCode, type)).isFile
    }

    // ---- pre-scrape bundled-cover fallback ------------------------------

    /**
     * Content uri of a cover file bundled inside the work folder
     * (cover.jpg/folder.jpg/封面*), or null. The lookup result is cached per
     * work in a bounded LRU map — including the negative result — so list
     * scrolling never re-lists the same folder over SAF (the plan's "no
     * repeated IO during list scrolling"). Pass null folder info (no local
     * row, e.g. remote works) → null, not a lookup.
     */
    suspend fun bundledCoverUri(rootFolderUri: String?, relativeDir: String?): String? {
        if (rootFolderUri.isNullOrBlank() || relativeDir.isNullOrBlank()) return null
        val key = "$rootFolderUri\u0000$relativeDir"
        return lock.withLock {
            if (bundledCache.contains(key)) {
                bundledCache[key] // cached value may legitimately be null (no bundled cover)
            } else {
                val uri = try {
                    bundledLocator.findBundledCover(rootFolderUri, relativeDir)
                } catch (e: Exception) {
                    null // unreadable folder / lost grant → fall back to placeholder
                }
                bundledCache[key] = uri
                uri
            }
        }
    }

    // ---- Coil model ------------------------------------------------------

    /**
     * The Coil model for a cover: the local cached [File] first, else the
     * bundled cover's content uri, else null — the caller renders the
     * placeholder for null. "Local file first, placeholder when missing".
     */
    suspend fun coverModelFor(
        rjCode: String,
        type: CoverType,
        rootFolderUri: String?,
        relativeDir: String?,
    ): Any? {
        localCoverFile(rjCode, type)?.let { return it }
        return bundledCoverUri(rootFolderUri, relativeDir)
    }

    // ---- cache management (Task 27 will surface this in Settings) --------

    /** Total bytes currently held in covers/ (tracked entries only). */
    suspend fun totalSizeBytes(): Long = lock.withLock {
        entries.values.sumOf { it.sizeBytes }
    }

    /** Deletes every cached cover. Playback data and reviews are untouched. */
    suspend fun clearAll() {
        lock.withLock {
            entries.keys.toList().forEach { path ->
                File(path).delete()
                entries.remove(path)
            }
        }
    }

    /** Enforces the cap now (LRU eviction); also called after every write. */
    suspend fun enforceCacheCap() {
        lock.withLock { enforceCapLocked() }
    }

    // ---- internals -------------------------------------------------------

    /**
     * Cap enforcement:
     * 1. Seed entries for files found on disk that we have not seen this
     *    process run (covers written before a restart): access time falls back
     *    to the file's lastModified — a reasonable persistence proxy — so a
     *    fresh process still respects the cap instead of treating pre-existing
     *    files as free.
     * 2. Drop bookkeeping for files deleted externally (never crash on a
     *    missing dir).
     * 3. While the total exceeds the cap AND more than one file remains,
     *    evict the least-recently-accessed file (explicit accessTime sort —
     *    never via an accessOrder map, whose reads reorder entries). At least
     *    one file is always kept: a single cover may exceed a tiny cap.
     */
    private fun enforceCapLocked() {
        val cap = cacheCapBytes().coerceAtLeast(0)
        val files = coversDir.listFiles() ?: return
        val onDisk = files.filter { it.isFile && !it.name.endsWith(PART_SUFFIX) }
        val onDiskPaths = onDisk.mapTo(HashSet()) { it.path }
        entries.keys.toList().forEach { path ->
            if (!onDiskPaths.contains(path)) entries.remove(path)
        }
        onDisk.forEach { f ->
            if (!entries.containsKey(f.path)) {
                entries[f.path] = Entry(f, maxOf(f.lastModified(), 0L), f.length())
            }
            entries.getValue(f.path).sizeBytes = f.length()
        }
        var total = entries.values.sumOf { it.sizeBytes }
        val lruOrder = entries.values.sortedBy { it.accessTime }.toMutableList()
        while (total > cap && entries.size > 1) {
            val eldest = lruOrder.removeAt(0)
            total -= eldest.sizeBytes
            eldest.file.delete()
            entries.remove(eldest.file.path)
        }
    }

    private companion object {
        const val TAG = "OneAsmrCover"
        /** In-flight download suffix; never counts toward the cap listing. */
        const val PART_SUFFIX = ".part"
    }
}

/**
 * Kikoeru cover filename: `{rjCode}_img_{type}.jpg` (bare rjCode, no source
 * prefix — KeySpec forbids it; the DB work id "local:RJ123456" must never
 * leak into file names).
 */
fun coverFileName(rjCode: String, type: CoverType): String {
    require(rjCode.isNotBlank()) { "rjCode must not be blank" }
    require(!rjCode.contains('/') && !rjCode.contains('\\') && !rjCode.contains("..")) {
        "rjCode must not contain path separators: $rjCode"
    }
    return "${rjCode}_img_${type.dirName}.jpg"
}

/**
 * DLsite work-page referer for cover downloads (anti-hotlink; the scraper
 * sends the same referer for the ajax request). Routing mirrors
 * [com.oneasmr.app.data.remote.dlsite.DlsiteScraper] siteFor().
 */
fun dlsiteRefererFor(rjCode: String): String {
    val site = when {
        rjCode.startsWith("BJ") -> "books"
        rjCode.startsWith("VJ") -> "pro"
        else -> "maniax"
    }
    return "https://www.dlsite.com/$site/work/=/product_id/$rjCode.html"
}
