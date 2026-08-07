package com.oneasmr.app.data.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 14: relativeDir (display-name path) → SAF document-id path resolution.
 * Pure JVM over the fake filesystem.
 */
class WorkPathResolverTest {

    private val fs = FakeDocumentFs()

    @Test
    fun `resolves multi-level work path`() {
        fs.addDirectory(emptyList(), "AsmrLib")
        val work = fs.addDirectory(listOf("AsmrLib"), "RJ123456")
        fs.addFile(work, "track1.mp3")

        val result = WorkPathResolver(fs).resolve("AsmrLib/RJ123456")

        assertTrue("expected Found, got $result", result is WorkPathResolver.Result.Found)
        result as WorkPathResolver.Result.Found
        assertEquals(listOf("AsmrLib", "RJ123456"), result.path.segments)
        assertEquals("RJ123456", result.displayName)
        assertEquals("content://fake/AsmrLib/RJ123456", result.documentUri)
    }

    @Test
    fun `single segment path resolves from the tree root`() {
        val work = fs.addDirectory(emptyList(), "RJ000001")
        fs.addFile(work, "a.mp3")

        val result = WorkPathResolver(fs).resolve("RJ000001")

        assertTrue(result is WorkPathResolver.Result.Found)
        result as WorkPathResolver.Result.Found
        assertEquals(listOf("RJ000001"), result.path.segments)
        assertEquals("RJ000001", result.displayName)
    }

    @Test
    fun `missing segment reports NotFound with the failed name`() {
        fs.addDirectory(emptyList(), "AsmrLib")

        val result = WorkPathResolver(fs).resolve("AsmrLib/RJ999999")

        assertTrue(result is WorkPathResolver.Result.NotFound)
        result as WorkPathResolver.Result.NotFound
        assertTrue(result.message.contains("RJ999999"))
    }

    @Test
    fun `file with the work name is not a directory - NotFound`() {
        fs.addFile(emptyList(), "RJ123456")

        val result = WorkPathResolver(fs).resolve("RJ123456")

        assertTrue(result is WorkPathResolver.Result.NotFound)
    }

    @Test
    fun `unreadable directory during resolution reports NotFound not crash`() {
        fs.addDirectory(emptyList(), "AsmrLib")
        fs.markUnreadable(listOf("AsmrLib"))

        val result = WorkPathResolver(fs).resolve("AsmrLib/RJ123456")

        assertTrue(result is WorkPathResolver.Result.NotFound)
    }

    @Test
    fun `blank relative dir reports NotFound`() {
        val result = WorkPathResolver(fs).resolve("")
        assertTrue(result is WorkPathResolver.Result.NotFound)
    }
}
