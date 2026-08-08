package com.oneasmr.app.data.local

import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Final-wave finding F2 regression tests for [DegradingPagingSource]: a
 * cancelled FTS load must rethrow [CancellationException] — the LIKE degrade
 * must never swallow a cancellation into a fallback page.
 */
class DegradingPagingSourceTest {

    /** Fake source that optionally throws on load and records whether it ran. */
    private class FakeSource(
        private val throwable: Throwable? = null,
        var loaded: Boolean = false,
    ) : PagingSource<Int, WorkListItem>() {
        override fun getRefreshKey(state: PagingState<Int, WorkListItem>): Int? = null

        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, WorkListItem> {
            loaded = true
            throwable?.let { throw it }
            return LoadResult.Page(emptyList(), prevKey = null, nextKey = null)
        }
    }

    private val refresh = PagingSource.LoadParams.Refresh<Int>(key = null, loadSize = 10, placeholdersEnabled = false)

    @Test
    fun `cancelled fts load rethrows instead of degrading to LIKE`() = runBlocking {
        val fts = FakeSource(throwable = CancellationException("cancelled"))
        val like = FakeSource()
        try {
            DegradingPagingSource(fts, like).load(refresh)
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            // expected: cancellation must propagate as-is
        }
        assertTrue(fts.loaded)
        assertFalse("the LIKE degrade must not run for a cancelled load", like.loaded)
    }

    @Test
    fun `non-cancellation fts failure still degrades to LIKE`() = runBlocking {
        val fts = FakeSource(throwable = IllegalStateException("FTS MATCH syntax error"))
        val like = FakeSource()
        val result = DegradingPagingSource(fts, like).load(refresh)
        assertTrue(like.loaded)
        assertTrue(result is PagingSource.LoadResult.Page)
    }
}
