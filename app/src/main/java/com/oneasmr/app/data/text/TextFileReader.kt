package com.oneasmr.app.data.text

import android.content.Context
import android.net.Uri
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads a document's raw bytes for the built-in text viewer (plan Task 14).
 * Injectable so the ViewModel is unit-testable with a fake byte source.
 */
fun interface TextFileReader {
    /** @throws IOException when the document is unreadable. */
    suspend fun read(documentUri: String): ByteArray
}

/**
 * Production reader over ContentResolver. Bounded read ([MAX_BYTES]) so a
 * pathological "text" file cannot exhaust memory; larger files are truncated
 * and the viewer surfaces the truncation.
 */
class SafTextFileReader @Inject constructor(context: Context) : TextFileReader {

    private val resolver = context.contentResolver

    override suspend fun read(documentUri: String): ByteArray = withContext(Dispatchers.IO) {
        val input = try {
            resolver.openInputStream(Uri.parse(documentUri))
        } catch (e: Exception) {
            throw IOException("无法打开文件 $documentUri: ${e.message}", e)
        } ?: throw IOException("无法打开文件 $documentUri（无内容流）")
        try {
            input.use { stream ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    val remaining = MAX_BYTES - total
                    if (remaining <= 0) {
                        out.write(TRUNCATION_MARKER)
                        break
                    }
                    val take = minOf(n, remaining)
                    out.write(buffer, 0, take)
                    total += take
                }
                out.toByteArray()
            }
        } catch (e: IOException) {
            throw IOException("读取文件失败 $documentUri: ${e.message}", e)
        }
    }

    companion object {
        const val MAX_BYTES = 4 * 1024 * 1024
        val TRUNCATION_MARKER = "\n\n[文件过大，仅显示前 ${MAX_BYTES / 1024 / 1024}MB]\n".toByteArray(Charsets.UTF_8)
    }
}
