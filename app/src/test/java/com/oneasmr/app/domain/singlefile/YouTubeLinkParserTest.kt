package com.oneasmr.app.domain.singlefile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeLinkParserTest {

    @Test
    fun `accepts common url shapes`() {
        val id = "dQw4w9WgXcQ"
        listOf(
            id,
            "  $id  ",
            "https://www.youtube.com/watch?v=$id",
            "https://www.youtube.com/watch?list=PL123&v=$id&t=42s",
            "https://youtu.be/$id",
            "https://youtu.be/$id?t=10",
            "https://www.youtube.com/shorts/$id",
            "https://www.youtube.com/embed/$id",
            "https://www.youtube.com/live/$id",
            "http://m.youtube.com/watch?v=$id",
        ).forEach { input ->
            assertEquals("input: $input", id, YouTubeLinkParser.extractId(input))
        }
    }

    @Test
    fun `rejects garbage`() {
        listOf(
            "",
            "   ",
            "not a link",
            "https://www.youtube.com/",
            "https://example.com/watch?v=short",
            "dQw4w9WgXc", // 10 位
            "dQw4w9WgXcQQ", // 12 位
        ).forEach { input ->
            assertNull("input: $input", YouTubeLinkParser.extractId(input))
        }
    }
}
