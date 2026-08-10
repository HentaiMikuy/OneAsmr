package com.oneasmr.app.data.scanner

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单文件扫描:媒体白名单过滤、同 stem 边车配对、递归与告警继续、
 * missing 差集规则。[FakeDocumentFs] fixture,全确定性。
 */
class SingleFileScannerTest {

    private class RecordingCallback : SingleFileScanner.Callback {
        val found = mutableListOf<SingleFileCandidate>()
        val warnings = mutableListOf<String>()
        var active = true

        override suspend fun onFileFound(candidate: SingleFileCandidate) {
            found += candidate
        }

        override fun onProgress(currentDir: String, filesFound: Int) = Unit

        override fun onWarning(message: String) {
            warnings += message
        }

        override fun isActive(): Boolean = active
    }

    @Test
    fun `collects media files and pairs same-stem sidecars`() = runBlocking {
        val fs = FakeDocumentFs()
        fs.addFile(name = "【ASMR】耳かき [dQw4w9WgXcQ].mp4", size = 100L)
        fs.addFile(name = "【ASMR】耳かき [dQw4w9WgXcQ].info.json", size = 5L)
        fs.addFile(name = "【ASMR】耳かき [dQw4w9WgXcQ].webp", size = 9L)
        fs.addFile(name = "裸音频.m4a", size = 50L)
        fs.addFile(name = "notes.txt", size = 1L) // 非音视频:不入候选
        fs.addFile(name = "cover.jpg", size = 2L) // 无同 stem 媒体:仅是普通图,忽略

        val cb = RecordingCallback()
        SingleFileScanner(fs).scanRoot(cb)

        assertEquals(2, cb.found.size)
        val video = cb.found.first { it.isVideo }
        assertEquals("【ASMR】耳かき [dQw4w9WgXcQ].mp4", video.relativePath)
        assertTrue(video.infoJsonUri!!.endsWith(".info.json"))
        assertTrue(video.thumbUri!!.endsWith(".webp"))
        val audio = cb.found.first { !it.isVideo }
        assertEquals("裸音频.m4a", audio.relativePath)
        assertNull(audio.infoJsonUri)
        assertNull(audio.thumbUri)
    }

    @Test
    fun `recurses subdirectories with relative paths`() = runBlocking {
        val fs = FakeDocumentFs()
        val sub = fs.addDirectory(name = "频道A")
        fs.addFile(parent = sub, name = "a.mp4", size = 1L)

        val cb = RecordingCallback()
        SingleFileScanner(fs).scanRoot(cb)

        assertEquals(listOf("频道A/a.mp4"), cb.found.map { it.relativePath })
    }

    @Test
    fun `rj work folders are skipped entirely`() = runBlocking {
        val fs = FakeDocumentFs()
        // 混合根:作品文件夹里的音轨绝不拍平成单文件,散文件照常收录。
        val work = fs.addDirectory(name = "RJ123456")
        fs.addFile(parent = work, name = "track01.mp3", size = 1L)
        val nested = fs.addDirectory(parent = work, name = "CD2")
        fs.addFile(parent = nested, name = "track02.mp3", size = 1L)
        val channel = fs.addDirectory(name = "频道A")
        fs.addFile(parent = channel, name = "loose.mp4", size = 1L)
        fs.addFile(name = "root.mp4", size = 1L)

        val cb = RecordingCallback()
        SingleFileScanner(fs).scanRoot(cb)

        assertEquals(listOf("root.mp4", "频道A/loose.mp4"), cb.found.map { it.relativePath }.sorted())
    }

    @Test
    fun `unreadable directory warns and scan continues`() = runBlocking {
        val fs = FakeDocumentFs()
        val bad = fs.addDirectory(name = "bad")
        fs.markUnreadable(bad)
        fs.addFile(name = "ok.mp4", size = 1L)

        val cb = RecordingCallback()
        SingleFileScanner(fs).scanRoot(cb)

        assertEquals(listOf("ok.mp4"), cb.found.map { it.relativePath })
        assertEquals(1, cb.warnings.size)
    }

    @Test(expected = ScanAbortedException::class)
    fun `inactive callback aborts`(): Unit = runBlocking {
        val fs = FakeDocumentFs()
        fs.addFile(name = "a.mp4")
        val cb = RecordingCallback().apply { active = false }
        SingleFileScanner(fs).scanRoot(cb)
    }

    @Test
    fun `sidecar pairing is case-insensitive on stem`() = runBlocking {
        val fs = FakeDocumentFs()
        fs.addFile(name = "Video [abcdefghijk].MP4", size = 1L)
        fs.addFile(name = "video [abcdefghijk].info.json", size = 1L)

        val cb = RecordingCallback()
        SingleFileScanner(fs).scanRoot(cb)

        assertTrue(cb.found.single().infoJsonUri != null)
    }
}

class SingleFileRescanDiffTest {

    private fun ref(id: Long, root: String = "content://tree/s", missing: Boolean = false) =
        SingleFileRescanDiff.StoredRef(id, root, missing)

    @Test
    fun `marks undiscovered rows under complete roots only`() {
        val result = SingleFileRescanDiff.computeMissing(
            discoveredIds = setOf(1L),
            stored = listOf(
                ref(1L), // 本轮已见:不标
                ref(2L), // 未见 + 完整根:标
                ref(3L, root = "content://tree/failed"), // 未完整枚举的根:不标
                ref(4L, missing = true), // 已标过:不重复
            ),
            completeRootUris = setOf("content://tree/s"),
        )
        assertEquals(listOf(2L), result)
    }
}
