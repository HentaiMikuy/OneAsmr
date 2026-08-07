package com.oneasmr.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the normative key spec (see [KeySpec] KDoc). Tasks 19/25/26 build on
 * these exact formats; any change here must be deliberate and cross-reviewed.
 */
class KeySpecTest {

    @Test
    fun `work id is sourceScope colon bare rjCode`() {
        assertEquals("local:RJ123456", KeySpec.workId("local", "RJ123456"))
        assertEquals("srv1:RJ123456", KeySpec.workId("srv1", "RJ123456"))
        assertEquals("srv2:BJ012345", KeySpec.workId("srv2", "BJ012345"))
    }

    @Test
    fun `track key is sourceScope colon bare rjCode colon trackIndex`() {
        assertEquals("local:RJ123456:3", KeySpec.trackKey("local", "RJ123456", 3))
        assertEquals("srv1:RJ123456:3", KeySpec.trackKey("srv1", "RJ123456", 3))
        assertEquals("local:RJ123456:0", KeySpec.trackKey("local", "RJ123456", 0))
    }

    @Test
    fun `track key embeds bare rjCode and never double-prefixes work id`() {
        val trackKey = KeySpec.trackKey("local", "RJ123456", 3)

        // The middle segment of a track key is the BARE rjCode, never work.id:
        // if the format wrongly embedded the work id, the middle segment would
        // be "local:RJ123456" and the whole key would have a double prefix.
        val parts = KeySpec.parseTrackKey(trackKey)!!
        assertEquals("RJ123456", parts.rjCode)
        assertNotEquals("local:RJ123456", parts.rjCode)

        // A track key must never be mistaken for a work id (distinguishable
        // shapes => no ambiguous "scope:workId:index" encoding).
        assertNull(KeySpec.parseWorkId(trackKey))

        // Explicitly reject the hypothetical double-prefixed encoding.
        val doublePrefixed = KeySpec.workId("local", "local:RJ123456")
        assertNotEquals(trackKey, "$doublePrefixed:3")
        assertTrue(trackKey.startsWith("local:RJ123456:"))
    }

    @Test
    fun `local and remote keys never collide`() {
        assertNotEquals(KeySpec.workId("local", "RJ123456"), KeySpec.workId("srv1", "RJ123456"))
        assertNotEquals(
            KeySpec.trackKey("local", "RJ123456", 1),
            KeySpec.trackKey("srv1", "RJ123456", 1),
        )
    }

    @Test
    fun `remote source scope is srvN`() {
        assertEquals("srv1", KeySpec.remoteSource(1))
        assertEquals("srv9", KeySpec.remoteSource(9))
    }

    @Test
    fun `work id parses back into scope and bare code`() {
        val parts = KeySpec.parseWorkId("local:RJ123456")!!
        assertEquals("local", parts.sourceScope)
        assertEquals("RJ123456", parts.rjCode)
        assertEquals("srv1", KeySpec.parseWorkId("srv1:RJ123456")!!.sourceScope)
    }

    @Test
    fun `malformed work ids are rejected`() {
        assertNull(KeySpec.parseWorkId("localRJ123456"))
        assertNull(KeySpec.parseWorkId("local:"))
        assertNull(KeySpec.parseWorkId(":RJ123456"))
        assertNull(KeySpec.parseWorkId("local:RJ123456:3"))
        assertNull(KeySpec.parseWorkId(""))
    }

    @Test
    fun `track key parses back into scope code and index`() {
        val parts = KeySpec.parseTrackKey("local:RJ123456:3")!!
        assertEquals("local", parts.sourceScope)
        assertEquals("RJ123456", parts.rjCode)
        assertEquals(3, parts.trackIndex)
    }

    @Test
    fun `malformed track keys are rejected`() {
        assertNull(KeySpec.parseTrackKey("local:RJ123456"))
        assertNull(KeySpec.parseTrackKey("local:RJ123456:x"))
        assertNull(KeySpec.parseTrackKey("local:RJ123456:-1"))
        assertNull(KeySpec.parseTrackKey("local:RJ123456:3:extra"))
        assertNull(KeySpec.parseTrackKey(""))
    }
}
