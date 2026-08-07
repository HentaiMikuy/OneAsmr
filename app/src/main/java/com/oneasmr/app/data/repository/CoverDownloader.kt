package com.oneasmr.app.data.repository

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Outcome of one cover download; every non-OK outcome means "no file, continue". */
enum class CoverDownloadResult { OK, NOT_FOUND, FAILED }

/**
 * Cover download transport, abstracted so CoverStore stays JVM-testable with
 * a fake (404 paths and failure isolation asserted without real network).
 */
interface CoverDownloader {
    /**
     * Downloads [url] into [target] (a fresh temp file — the caller renames
     * into place). Never throws: HTTP 404 → [CoverDownloadResult.NOT_FOUND],
     * any other failure → [CoverDownloadResult.FAILED].
     */
    suspend fun download(url: String, referer: String?, target: File): CoverDownloadResult
}

/**
 * OkHttp cover downloader. Sends the same browser User-Agent the scraper uses
 * and the work-page [referer] (DLsite hotlink protection; Task 9 learning:
 * cover URLs 403 without a browser UA + referer). Writes into [target]
 * directly; the caller owns the atomic tmp→final rename.
 */
class OkHttpCoverDownloader(
    private val client: OkHttpClient = defaultClient(),
) : CoverDownloader {

    override suspend fun download(url: String, referer: String?, target: File): CoverDownloadResult =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(url).header("User-Agent", USER_AGENT)
            if (referer != null) builder.header("Referer", referer)
            val response = try {
                client.newCall(builder.build()).execute()
            } catch (e: IOException) {
                return@withContext CoverDownloadResult.FAILED
            }
            response.use { resp ->
                if (resp.code == 404) return@withContext CoverDownloadResult.NOT_FOUND
                if (!resp.isSuccessful) return@withContext CoverDownloadResult.FAILED
                val body = resp.body ?: return@withContext CoverDownloadResult.FAILED
                try {
                    body.byteStream().use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    CoverDownloadResult.OK
                } catch (e: IOException) {
                    target.delete()
                    CoverDownloadResult.FAILED
                }
            }
        }

    private companion object {
        /** Same desktop Chrome UA as DlsiteScraper — DLsite rejects bare OkHttp. */
        const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
