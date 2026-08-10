package com.oneasmr.app.data.remote.youtube

import android.util.Log
import com.oneasmr.app.data.remote.RequestPacer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/** oEmbed 补全结果(YouTube 无需 API key 暴露的公开元数据面)。 */
data class YouTubeOEmbed(
    val title: String,
    /** 频道名(oEmbed author_name)。 */
    val authorName: String?,
)

/**
 * YouTube oEmbed 客户端(单档库「在线补全」,与作品的 DLsite 刮削同
 * 心智:手动触发、全局 [RequestPacer] 1s 节流、结构化失败)。
 *
 * - 元数据:GET https://www.youtube.com/oembed?url=https://youtu.be/{id}
 *   —— 无需 API key;404/401 = 视频不存在或不可外嵌。
 * - 封面:i.ytimg.com/vi/{id}/maxresdefault.jpg,404 回落 hqdefault.jpg
 *   (maxres 只有部分视频有;hqdefault 对所有视频存在)。
 *
 * 调用方须在 IO 线程(OkHttp execute 阻塞)。失败一律返回 null + 日志,
 * 上层把行标为 FAILED —— 与刮削一致的「非阻塞、可重试」语义。
 */
class YouTubeOEmbedClient(
    private val client: OkHttpClient,
    private val pacer: RequestPacer,
) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchMeta(videoId: String): YouTubeOEmbed? {
        pacer.pace()
        val url = "https://www.youtube.com/oembed?url=https%3A%2F%2Fyoutu.be%2F$videoId&format=json"
        return runCatching {
            client.newCall(request(url)).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "oembed HTTP ${resp.code} for $videoId")
                    return null
                }
                val obj = json.parseToJsonElement(resp.body?.string().orEmpty()) as? JsonObject
                    ?: return null
                val title = (obj["title"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                    ?: return null
                YouTubeOEmbed(
                    title = title,
                    authorName = (obj["author_name"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() },
                )
            }
        }.getOrElse { e ->
            Log.w(TAG, "oembed fetch failed for $videoId: ${e.message}")
            null
        }
    }

    /** 封面 JPEG 字节;maxres 缺失(404)回落 hqdefault,再失败返回 null。 */
    suspend fun fetchThumbnail(videoId: String): ByteArray? {
        for (variant in listOf("maxresdefault", "hqdefault")) {
            pacer.pace()
            val bytes = runCatching {
                client.newCall(request("https://i.ytimg.com/vi/$videoId/$variant.jpg")).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.bytes() else null
                }
            }.getOrElse { e ->
                Log.w(TAG, "thumbnail fetch failed ($variant) for $videoId: ${e.message}")
                null
            }
            if (bytes != null && bytes.isNotEmpty()) return bytes
        }
        return null
    }

    private fun request(url: String): Request = Request.Builder()
        .url(url)
        .header("User-Agent", USER_AGENT)
        .build()

    private companion object {
        const val TAG = "OneAsmrYtOEmbed"

        /** 与 DLsite 刮削同款桌面 UA(默认 OkHttp UA 在部分 CDN 上被拒)。 */
        const val USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
