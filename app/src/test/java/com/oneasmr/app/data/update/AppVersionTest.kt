package com.oneasmr.app.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {

    @Test
    fun `parses plain and v-prefixed versions`() {
        assertEquals(AppVersion(0, 1, 0), AppVersion.parse("0.1.0"))
        assertEquals(AppVersion(0, 2, 0), AppVersion.parse("v0.2.0"))
        assertEquals(AppVersion(1, 0, 0), AppVersion.parse("1.0"))
        assertEquals(AppVersion(0, 3, 1), AppVersion.parse("v0.3.1-rc1"))
    }

    @Test
    fun `rejects unparseable input`() {
        assertNull(AppVersion.parse(""))
        assertNull(AppVersion.parse("abc"))
        assertNull(AppVersion.parse("0.1.0.1"))
        assertNull(AppVersion.parse("v"))
    }

    @Test
    fun `orders numerically not lexically`() {
        assertTrue(AppVersion.parse("v0.10.0")!! > AppVersion.parse("v0.9.9")!!)
        assertTrue(AppVersion.parse("0.2.0")!! > AppVersion.parse("0.1.99")!!)
        assertEquals(AppVersion.parse("0.1.0"), AppVersion.parse("v0.1.0"))
    }
}
