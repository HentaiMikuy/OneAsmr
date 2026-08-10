package com.oneasmr.app.domain.singlefile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 单文件元数据解析:文件名尾部 ID 剥离规则、info.json 宽容解析、
 * 逐字段合并优先级。fixture 全部合成,无 IO。
 */
class SingleFileMetadataParserTest {

    // ---- fromFileName ----

    @Test
    fun `yt-dlp default template extracts trailing id and title`() {
        val meta = SingleFileMetadataParser.fromFileName(
            "【ASMR】囁き耳かき＆マッサージ🌙 [dQw4w9WgXcQ].mp4",
        )
        assertEquals("【ASMR】囁き耳かき＆マッサージ🌙", meta.displayTitle)
        assertEquals("dQw4w9WgXcQ", meta.youtubeId)
    }

    @Test
    fun `plain filename keeps title and has no id`() {
        val meta = SingleFileMetadataParser.fromFileName("耳かきASMR 睡眠導入.webm")
        assertEquals("耳かきASMR 睡眠導入", meta.displayTitle)
        assertNull(meta.youtubeId)
    }

    @Test
    fun `legitimate brackets in title are not stripped when length differs`() {
        // 12 字符方括号段:不是 11 位 ID,必须保留在标题里。
        val meta = SingleFileMetadataParser.fromFileName("ASMR [binaural rec].mp4")
        assertEquals("ASMR [binaural rec]", meta.displayTitle)
        assertNull(meta.youtubeId)
    }

    @Test
    fun `mid-title bracket segment is not treated as id`() {
        val meta = SingleFileMetadataParser.fromFileName("[dQw4w9WgXcQ] のあとに続くタイトル.mp4")
        assertEquals("[dQw4w9WgXcQ] のあとに続くタイトル", meta.displayTitle)
        assertNull(meta.youtubeId)
    }

    @Test
    fun `hyphen-id legacy form is deliberately not parsed`() {
        val meta = SingleFileMetadataParser.fromFileName("title-dQw4w9WgXcQ.mp4")
        assertEquals("title-dQw4w9WgXcQ", meta.displayTitle)
        assertNull(meta.youtubeId)
    }

    @Test
    fun `no extension filename falls back to whole name`() {
        val meta = SingleFileMetadataParser.fromFileName("noext")
        assertEquals("noext", meta.displayTitle)
    }

    @Test
    fun `title that empties after id strip falls back to stem but keeps id`() {
        val meta = SingleFileMetadataParser.fromFileName("[dQw4w9WgXcQ].mp4")
        assertEquals("[dQw4w9WgXcQ]", meta.displayTitle)
        // ID 照常认领:在线补全正要靠它找回真实标题。
        assertEquals("dQw4w9WgXcQ", meta.youtubeId)
    }

    // ---- fromInfoJson ----

    @Test
    fun `info json parses full field set`() {
        val meta = SingleFileMetadataParser.fromInfoJson(
            """
            {
              "id": "dQw4w9WgXcQ",
              "title": "【ASMR】ぐっすり眠れる耳かき",
              "channel": "しーくれっとぼいす",
              "uploader": "secretvoice ch",
              "upload_date": "20240315",
              "duration": 3721.5,
              "webpage_url": "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
              "unknown_field": {"nested": true}
            }
            """.trimIndent(),
        )!!
        assertEquals("【ASMR】ぐっすり眠れる耳かき", meta.displayTitle)
        assertEquals("dQw4w9WgXcQ", meta.youtubeId)
        assertEquals("しーくれっとぼいす", meta.channel) // channel 优先于 uploader
        assertEquals("2024-03-15", meta.uploadDate)
        assertEquals(3_721_500L, meta.durationMs)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", meta.sourceUrl)
    }

    @Test
    fun `uploader fills channel when channel missing`() {
        val meta = SingleFileMetadataParser.fromInfoJson(
            """{"title": "t", "uploader": "someone"}""",
        )!!
        assertEquals("someone", meta.channel)
    }

    @Test
    fun `integer duration and bad id are tolerated`() {
        val meta = SingleFileMetadataParser.fromInfoJson(
            """{"title": "t", "duration": 60, "id": "short"}""",
        )!!
        assertEquals(60_000L, meta.durationMs)
        assertNull(meta.youtubeId) // 非 11 位不认
    }

    @Test
    fun `malformed json returns null`() {
        assertNull(SingleFileMetadataParser.fromInfoJson("not json at all {"))
        assertNull(SingleFileMetadataParser.fromInfoJson("[1,2,3]")) // 非对象
        assertNull(SingleFileMetadataParser.fromInfoJson("""{"no_title": 1}"""))
    }

    // ---- merge ----

    @Test
    fun `merge prefers primary per field and falls back per field`() {
        val fromName = SingleFileMetadataParser.fromFileName("fallback title [dQw4w9WgXcQ].mp4")
        val fromJson = SingleFileMeta(
            displayTitle = "json title",
            youtubeId = null,
            channel = "ch",
            uploadDate = null,
            durationMs = 1000L,
            sourceUrl = null,
        )
        val merged = SingleFileMetadataParser.merge(fromJson, fromName)
        assertEquals("json title", merged.displayTitle)
        assertEquals("dQw4w9WgXcQ", merged.youtubeId) // json 缺 id → 文件名补
        assertEquals("ch", merged.channel)
        assertEquals(1000L, merged.durationMs)
    }

    @Test
    fun `merge with null primary returns fallback`() {
        val fallback = SingleFileMetadataParser.fromFileName("a.mp4")
        assertEquals(fallback, SingleFileMetadataParser.merge(null, fallback))
    }
}
