package com.oneasmr.app.ui.work

import androidx.lifecycle.SavedStateHandle
import com.oneasmr.app.data.text.TextFileReader
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Task 14 text viewer: auto-detect decode, the no-mojibake menu path on
 * detection failure, manual encoding switch, and IO errors. Virtual scheduler
 * only — no real-time waits (repo flake convention).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TextFileViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var reader: FakeTextFileReader

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        reader = FakeTextFileReader()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel(): TextFileViewModel =
        TextFileViewModel(
            savedStateHandle = SavedStateHandle(mapOf("documentUri" to "content://fake/text.txt")),
            reader = reader,
        )

    private fun encode(text: String, charset: String): ByteArray {
        val buffer = java.nio.charset.Charset.forName(charset).encode(text)
        val out = ByteArray(buffer.remaining())
        buffer.get(out)
        return out
    }

    @Test
    fun `utf8 file shows decoded text without menu`() = runTest(scheduler) {
        reader.bytes = encode("こんにちは、世界。", "UTF-8")

        val vm = newViewModel()
        val state = vm.uiState.first { !it.loading }

        assertEquals("こんにちは、世界。", state.text)
        assertEquals("UTF-8", state.selectedEncoding)
        assertFalse(state.showEncodingMenu)
    }

    @Test
    fun `shift_jis file decodes correctly`() = runTest(scheduler) {
        reader.bytes = encode("おやすみなさい、また明日。", "Shift_JIS")

        val vm = newViewModel()
        val state = vm.uiState.first { !it.loading }

        assertEquals("おやすみなさい、また明日。", state.text)
        assertFalse(state.showEncodingMenu)
        assertNotNull(state.selectedEncoding)
    }

    @Test
    fun `unrecognizable encoding shows the menu and never mojibake`() = runTest(scheduler) {
        reader.bytes = ByteArray(0)

        val vm = newViewModel()
        val state = vm.uiState.first { !it.loading }

        assertTrue(state.showEncodingMenu)
        assertNull("unverified text must never be rendered", state.text)
    }

    @Test
    fun `corrupt bytes show the menu or an error but never garbage text`() = runTest(scheduler) {
        reader.bytes = byteArrayOf(
            0xC3.toByte(), 0x28.toByte(), 0xE2.toByte(), 0x82.toByte(), 0xAC.toByte(),
            0xC0.toByte(), 0xAF.toByte(), 0xF5.toByte(), 0x80.toByte(), 0x80.toByte(),
        )

        val vm = newViewModel()
        val state = vm.uiState.first { !it.loading }

        assertNull(state.text)
        assertTrue(state.showEncodingMenu || state.error != null)
    }

    @Test
    fun `read failure surfaces an error`() = runTest(scheduler) {
        reader.failure = IOException("boom")

        val vm = newViewModel()
        val state = vm.uiState.first { !it.loading }

        assertNotNull(state.error)
        assertNull(state.text)
    }

    @Test
    fun `manual encoding switch to the correct charset fixes the text`() = runTest(scheduler) {
        reader.bytes = encode("你好，世界。", "GBK")
        // Force the viewer into the menu state by simulating a bad auto guess:
        val vm = newViewModel()
        vm.uiState.first { !it.loading }
        vm.selectEncoding("UTF-8") // wrong: GBK bytes are not valid UTF-8
        vm.uiState.first { it.selectedEncoding == "UTF-8" && !it.loading }

        vm.selectEncoding("GB18030")
        val state = vm.uiState.first { it.selectedEncoding == "GB18030" }

        assertEquals("你好，世界。", state.text)
        assertFalse(state.showEncodingMenu)
        assertNull(state.warning)
    }

    @Test
    fun `wrong manual encoding keeps the menu open`() = runTest(scheduler) {
        reader.bytes = encode("おやすみなさい、また明日。", "Shift_JIS")

        val vm = newViewModel()
        vm.uiState.first { !it.loading }
        vm.selectEncoding("UTF-8")

        val state = vm.uiState.first { !it.loading && it.selectedEncoding == "UTF-8" }
        assertTrue(state.showEncodingMenu)
        assertNull("wrong-encoding text must not be rendered", state.text)
    }

    private class FakeTextFileReader : TextFileReader {
        var bytes: ByteArray = ByteArray(0)
        var failure: IOException? = null

        override suspend fun read(documentUri: String): ByteArray {
            failure?.let { throw it }
            return bytes
        }
    }
}
