package com.oneasmr.app.data.update

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowDownloadManager.ShadowRequest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SystemUpdateDownloaderTest {
    private lateinit var context: Context
    private lateinit var manager: DownloadManager
    private val info = UpdateInfo("99.0.0", "https://github.com/example/releases/download/v99.0.0/app.apk", 3, "说明")

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        manager = context.getSystemService(DownloadManager::class.java)
        context.getSharedPreferences("update_download", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun request(): ShadowRequest {
        val id = manager.query(DownloadManager.Query()).use {
            assertTrue(it.moveToFirst())
            it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
        }
        return Shadow.extract(shadowOf(manager).getRequest(id))
    }

    private fun complete(bytes: ByteArray = byteArrayOf(1, 2, 3)): File {
        val request = request()
        val file = File(request.destination.path!!)
        file.parentFile!!.mkdirs()
        file.writeBytes(bytes)
        request.setStatus(DownloadManager.STATUS_SUCCESSFUL)
        request.setBytesSoFar(bytes.size.toLong())
        request.setTotalSize(bytes.size.toLong())
        return file
    }

    @Test
    fun `recreation restores the same running task and repeated enqueue does not duplicate it`() = runBlocking {
        val downloader = SystemUpdateDownloader(context)
        downloader.enqueue(info)
        request().setStatus(DownloadManager.STATUS_RUNNING)
        request().setBytesSoFar(2)
        val restored = SystemUpdateDownloader(context)
        val state = restored.observe().first() as UpdateDownloadState.Downloading
        assertEquals(2L, state.downloaded)
        assertEquals(info, state.info)
        restored.enqueue(info)
        assertEquals(1, shadowOf(manager).requestCount)
        assertEquals(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED, request().notificationVisibility)
    }

    @Test
    fun `completed download survives recreation and FileProvider exposes only its download folder`() = runBlocking {
        SystemUpdateDownloader(context).enqueue(info)
        val file = complete()
        val state = SystemUpdateDownloader(context).observe().first() as UpdateDownloadState.Downloaded
        assertEquals(file, state.file)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", state.file)
        assertEquals("content", uri.scheme)
        assertEquals("/system_update_apk/${file.name}", uri.path)
    }

    @Test
    fun `truncated completed download is removed and can be downloaded again`() = runBlocking {
        val downloader = SystemUpdateDownloader(context)
        downloader.enqueue(info)
        val file = complete(byteArrayOf(1))
        val state = downloader.observe().first() as UpdateDownloadState.Failed
        assertEquals(info, state.info)
        assertFalse(file.exists())
        assertEquals(0, shadowOf(manager).requestCount)
        downloader.enqueue(info)
        assertEquals(1, shadowOf(manager).requestCount)
    }

    @Test
    fun `missing system task returns a retryable error instead of waiting forever`() = runBlocking {
        val downloader = SystemUpdateDownloader(context)
        downloader.enqueue(info)
        val id = request().id
        manager.remove(id)
        val state = downloader.observe().first() as UpdateDownloadState.Failed
        assertEquals(info, state.info)
        assertTrue(state.message.contains("已被移除"))
        downloader.enqueue(info)
        assertEquals(1, shadowOf(manager).requestCount)
    }

    @Test
    fun `failed system task removes its partial file`() = runBlocking {
        val downloader = SystemUpdateDownloader(context)
        downloader.enqueue(info)
        val file = complete(byteArrayOf(1))
        request().setStatus(DownloadManager.STATUS_FAILED)
        assertTrue(downloader.observe().first() is UpdateDownloadState.Failed)
        assertFalse(file.exists())
        assertEquals(0, shadowOf(manager).requestCount)
    }

    @Test
    fun `corrupt persisted task does not permanently block future downloads`() = runBlocking {
        context.getSharedPreferences("update_download", Context.MODE_PRIVATE).edit().putString("task", "broken").commit()
        val downloader = SystemUpdateDownloader(context)
        assertTrue(downloader.observe().first() is UpdateDownloadState.Failed)
        downloader.enqueue(info)
        assertEquals(1, shadowOf(manager).requestCount)
    }

    @Test(expected = IOException::class)
    fun `non HTTPS download address is refused`() = runBlocking {
        SystemUpdateDownloader(context).enqueue(info.copy(downloadUrl = "http://example.com/app.apk"))
    }
}
