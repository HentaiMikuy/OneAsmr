package com.oneasmr.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SanityTest {
    @Test
    fun launcherActivityUsesApplicationPackage() {
        assertEquals("com.oneasmr.app.MainActivity", MainActivity::class.java.name)
    }
}
