package com.oneasmr.app.player

import android.graphics.Bitmap
import android.net.Uri
import com.oneasmr.app.data.repository.CoverDownloadResult
import com.oneasmr.app.data.repository.CoverDownloader
import com.oneasmr.app.data.repository.CoverStore
import com.oneasmr.app.data.repository.BundledCoverLocator
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Notification-cover resolution (CoverArtBitmapLoader):
 * - censored=1 → the default lock placeholder, and the real cover is never
 *   decoded (safe mode's hard constraint on the notification shelf)
 * - uncensored → the local scraped cover; missing/failed → placeholder
 * - foreign URIs (single-file thumbnails) go through the generic decode path
 *
 * Robolectric's native graphics give us a real BitmapFactory, so the cover
 * file is a genuine 1×1 PNG (distinct from the 512px placeholder).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoverArtBitmapLoaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 1×1 valid PNG — decodes to a bitmap of width 1, unlike the 512px placeholder. */
    private val png1x1: ByteArray = java.util.Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
    )

    private class NoopDownloader : CoverDownloader {
        override suspend fun download(url: String, referer: String?, target: File): CoverDownloadResult =
            CoverDownloadResult.FAILED
    }

    private class NoopLocator : BundledCoverLocator {
        override suspend fun findBundledCover(rootFolderUri: String, relativeDir: String): String? = null
    }

    private fun loaderWithCovers(dir: File): CoverArtBitmapLoader =
        CoverArtBitmapLoader(
            RuntimeEnvironment.getApplication(),
            CoverStore(dir, NoopDownloader(), NoopLocator(), { Long.MAX_VALUE }),
        )

    private fun loadBitmap(loader: CoverArtBitmapLoader, uri: Uri): Bitmap =
        loader.loadBitmap(uri).get(5, TimeUnit.SECONDS)

    @Test
    fun `censored ref renders the placeholder and never decodes the real cover`(): Unit = runBlocking {
        val dir = tmp.newFolder("covers")
        writeCover(dir, "RJ100200")
        val loader = loaderWithCovers(dir)
        val bitmap = loadBitmap(loader, coverArtUri("local", "RJ100200", censored = true))
        assertEquals(512, bitmap.width)
        assertEquals(512, bitmap.height)
    }

    @Test
    fun `uncensored with a local cover decodes the cover file`(): Unit = runBlocking {
        val dir = tmp.newFolder("covers")
        writeCover(dir, "RJ100200")
        val loader = loaderWithCovers(dir)
        val bitmap = loadBitmap(loader, coverArtUri("local", "RJ100200", censored = false))
        assertEquals(1, bitmap.width)
    }

    @Test
    fun `uncensored without any local cover falls back to the placeholder`(): Unit = runBlocking {
        val dir = tmp.newFolder("covers")
        val loader = loaderWithCovers(dir)
        val bitmap = loadBitmap(loader, coverArtUri("local", "RJ300300", censored = false))
        assertEquals(512, bitmap.width)
    }

    @Test
    fun `foreign uri decodes through the generic path`(): Unit = runBlocking {
        val file = tmp.newFile("thumb.png").apply { writeBytes(png1x1) }
        val loader = loaderWithCovers(tmp.newFolder("covers"))
        val bitmap = loadBitmap(loader, Uri.fromFile(file))
        assertEquals(1, bitmap.width)
    }

    private fun writeCover(coversDir: File, rjCode: String) {
        File(coversDir, "${rjCode}_img_main.jpg").writeBytes(png1x1)
    }
}
