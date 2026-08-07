package com.oneasmr.app.data.scanner

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Work-folder discovery over a fake filesystem (plan Task 6):
 * - happy QA: 3 works / 40 files, all discovered with correct relativeDir +
 *   rjCode, deterministic discovery order, progress sequence correct;
 * - nested RJ: an RJ folder inside a work folder is NOT registered (the work
 *   folder is never recursed into);
 * - failure QA: an unreadable directory is skipped with a warning and the
 *   scan continues;
 * - cancel: flipping isActive stops the scan; already-found works are the
 *   only ones reported (committed ones survive — persistence atomicity is
 *   locked in ScanPersisterTest).
 */
class LibraryScannerTest {

    private fun recordingCallback(
        found: MutableList<WorkCandidate>,
        progress: MutableList<Pair<String, Int>> = mutableListOf(),
        warnings: MutableList<String> = mutableListOf(),
        isActive: () -> Boolean = { true },
    ) = object : ScanCallback {
        override suspend fun onWorkFound(work: WorkCandidate) {
            found += work
        }

        override fun onProgress(currentDir: String, worksFound: Int) {
            progress += currentDir to worksFound
        }

        override fun onWarning(message: String) {
            warnings += message
        }

        override fun isActive(): Boolean = isActive()
    }

    /** Runs a full root scan (all top-level chunks) with [callback]. */
    private suspend fun LibraryScanner.scanAll(callback: ScanCallback) {
        for (chunk in topLevelChunks()) {
            scanChunk(chunk, callback)
        }
    }

    // ---------- happy path ----------

    @Test
    fun `happy QA three works forty files all discovered and ordered`() = runTest {
        val fs = FakeDocumentFs()
        // Root children (added out of natural order on purpose: the scanner
        // sorts per directory, discovery must be deterministic).
        fs.addFile(name = "readme.txt")
        val rj1 = fs.addDirectory(name = "RJ111111")
        val archives = fs.addDirectory(name = "Archives")
        val audioBooks = fs.addDirectory(name = "AudioBooks")

        // Work 1: RJ111111 with 15 files + a nested RJ trap that must NOT be found.
        repeat(15) { i -> fs.addFile(rj1, "track${i + 1}.mp3") }
        val trap = fs.addDirectory(rj1, "RJ999999")
        repeat(3) { i -> fs.addFile(trap, "t${i}.mp3") }

        // Work 2: AudioBooks/RJ222222 with 15 files (work nested one level).
        val rj2 = fs.addDirectory(audioBooks, "RJ222222")
        repeat(15) { i -> fs.addFile(rj2, "track${i + 1}.mp3") }

        // Work 3: Archives/BJ012345 with 10 files (BJ prefix, one level).
        val rj3 = fs.addDirectory(archives, "BJ012345")
        repeat(10) { i -> fs.addFile(rj3, "track${i + 1}.mp3") }

        val totalFixtureFiles =
            fs.entriesUnder(rj1).count { !it.isDirectory } +
                fs.entriesUnder(rj2).size +
                fs.entriesUnder(rj3).size
        assertEquals("fixture sanity: 40 files total", 40, totalFixtureFiles)

        val found = mutableListOf<WorkCandidate>()
        val progress = mutableListOf<Pair<String, Int>>()
        val warnings = mutableListOf<String>()
        val scanner = LibraryScanner(fs)
        scanner.scanAll(recordingCallback(found, progress, warnings))

        // All three works, none of the trap.
        assertEquals(3, found.size)
        assertEquals(
            setOf("Archives/BJ012345", "AudioBooks/RJ222222", "RJ111111"),
            found.map { it.relativeDir }.toSet(),
        )
        assertEquals(
            setOf("BJ012345", "RJ222222", "RJ111111"),
            found.map { it.rjCode }.toSet(),
        )
        assertFalse("nested RJ inside a work folder must NOT be discovered", found.any { it.rjCode == "RJ999999" })
        assertTrue("root-level files are not works", found.none { it.relativeDir == "readme.txt" })

        // Deterministic discovery order: root children are naturally sorted,
        // so Archives (BJ012345) -> AudioBooks (RJ222222) -> RJ111111.
        assertEquals(
            listOf("Archives/BJ012345", "AudioBooks/RJ222222", "RJ111111"),
            found.map { it.relativeDir },
        )

        // Progress: worksFound reaches 3; last heartbeat is the last work dir.
        assertTrue(
            "worksFound must be monotonic and reach 3",
            progress.map { it.second } == progress.map { it.second }.sorted() && progress.last().second == 3,
        )
        assertEquals("RJ111111", progress.last().first)
        assertEquals(0, warnings.size)
    }

    @Test
    fun `nested rj inside a work folder is ignored`() = runTest {
        val fs = FakeDocumentFs()
        val outer = fs.addDirectory(name = "RJ123456")
        fs.addFile(outer, "track1.mp3")
        fs.addDirectory(outer, "RJ123457") // inside a work folder: must NOT register
        fs.addDirectory(outer, "sub")
        // A real second work elsewhere is still found.
        fs.addDirectory(name = "RJ654321")

        val found = mutableListOf<WorkCandidate>()
        LibraryScanner(fs).scanAll(recordingCallback(found))

        // RJ123456 itself is a top-level work; RJ123457 inside it is ignored.
        assertEquals(listOf("RJ123456", "RJ654321"), found.map { it.rjCode })
        assertFalse(found.any { it.rjCode == "RJ123457" })
    }

    @Test
    fun `work folder names are matched case-insensitively`() = runTest {
        val fs = FakeDocumentFs()
        fs.addDirectory(name = "rj123456")
        fs.addDirectory(name = "vj00000000")

        val found = mutableListOf<WorkCandidate>()
        LibraryScanner(fs).scanAll(recordingCallback(found))

        assertEquals(setOf("RJ123456", "VJ00000000"), found.map { it.rjCode }.toSet())
        // rjCode carries the normalized uppercase prefix (KeySpec canonical form).
        assertTrue(found.all { it.rjCode == it.rjCode.uppercase() })
    }

    @Test
    fun `no rj folders anywhere yields no works`() = runTest {
        val fs = FakeDocumentFs()
        fs.addDirectory(name = "Music")
        fs.addDirectory(name = "123456") // digits without prefix: not a work
        fs.addDirectory(name = "RJ12345") // 5 digits: not a work
        fs.addDirectory(name = "RJ1234567") // 7 digits: not a work
        fs.addFile(name = "RJ123456.mp3") // file, not a folder

        val found = mutableListOf<WorkCandidate>()
        LibraryScanner(fs).scanAll(recordingCallback(found))

        assertEquals(0, found.size)
    }

    // ---------- failure path ----------

    @Test
    fun `failure QA unreadable directory is skipped with warning and scan continues`() = runTest {
        val fs = FakeDocumentFs()
        val ok1 = fs.addDirectory(name = "OK1")
        fs.addDirectory(ok1, "RJ123456")
        val noAccess = fs.addDirectory(name = "NoAccess")
        fs.addDirectory(noAccess, "RJ654321") // inside the unreadable dir: lost
        val ok2 = fs.addDirectory(name = "OK2")
        fs.addDirectory(ok2, "RJ234567")
        fs.markUnreadable(noAccess)

        val found = mutableListOf<WorkCandidate>()
        val warnings = mutableListOf<String>()
        LibraryScanner(fs).scanAll(recordingCallback(found, warnings = warnings))

        assertEquals(setOf("RJ123456", "RJ234567"), found.map { it.rjCode }.toSet())
        assertEquals(1, warnings.size)
        assertTrue("warning must name the skipped dir", warnings.single().contains("NoAccess"))
    }

    @Test
    fun `unreadable root chunk aborts the whole scan`() = runTest {
        val fs = FakeDocumentFs()
        fs.markUnreadable(emptyList()) // the tree root itself is unreadable

        val scanner = LibraryScanner(fs)
        val e = try {
            scanner.topLevelChunks()
            null
        } catch (ex: DocumentReadException) {
            ex
        }
        assertTrue("DocumentReadException must propagate from the root listing", e != null)
        assertTrue(e!!.message!!.contains("permission denied"))
    }

    // ---------- cancellation ----------

    @Test
    fun `cancel mid scan stops at the next directory boundary and reports only found works`() = runTest {
        val fs = FakeDocumentFs()
        val archives = fs.addDirectory(name = "Archives")
        fs.addDirectory(archives, "BJ012345") // found first
        fs.addDirectory(name = "AudioBooks") // RJ222222 would be next — never reached
        fs.addDirectory(name = "RJ111111")

        val found = mutableListOf<WorkCandidate>()
        val progress = mutableListOf<Pair<String, Int>>()
        var active = true

        val scanner = LibraryScanner(fs)
        val e = try {
            for (chunk in scanner.topLevelChunks()) {
                scanner.scanChunk(
                    chunk,
                    object : ScanCallback {
                        override suspend fun onWorkFound(work: WorkCandidate) {
                            found += work
                            active = false // cancel right after the first work
                        }

                        override fun onProgress(currentDir: String, worksFound: Int) {
                            progress += currentDir to worksFound
                        }

                        override fun onWarning(message: String) {}

                        override fun isActive(): Boolean = active
                    },
                )
            }
            null
        } catch (ex: ScanAbortedException) {
            ex
        }

        assertTrue("scan must abort with ScanAbortedException", e != null)
        assertEquals("exactly the works found before cancellation", 1, found.size)
        assertEquals(listOf("Archives/BJ012345"), found.map { it.relativeDir })
        assertEquals("progress stops at the found work", "Archives/BJ012345", progress.last().first)
        assertEquals(1, progress.last().second)
    }

    @Test
    fun `never-cancelled scan does not throw`() = runTest {
        val fs = FakeDocumentFs()
        fs.addDirectory(name = "RJ123456")
        val found = mutableListOf<WorkCandidate>()
        LibraryScanner(fs).scanAll(recordingCallback(found))
        assertEquals(1, found.size)
    }
}
