package com.oneasmr.app.data.update

import android.content.Context
import com.oneasmr.app.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Update transport: queries the GitHub "latest release" API for the newest
 * version and downloads the attached APK into the cache dir. Kept as a
 * thin interface so the ViewModel and the app-startup check are JVM-testable
 * against a fake.
 */
interface UpdateRepository {
    /**
     * Compares the latest GitHub release against the installed version.
     * Returns [UpdateCheckResult.UpToDate] when the app is current (or when
     * the remote reference is missing/unparseable), [UpdateCheckResult.NewVersion]
     * when a newer APK release exists, or [UpdateCheckResult.Error] when the
     * check itself failed (network / HTTP / payload).
     */
    suspend fun checkForUpdate(): UpdateCheckResult

    /** Downloads [info]'s APK into the cache dir; returns the local file. */
    suspend fun downloadApk(info: UpdateInfo, onProgress: (downloaded: Long, total: Long) -> Unit): File
}

/**
 * GitHub-backed implementation. Uses the public latest-release API of this
 * repository (no auth; unauthenticated rate limit 60 req/h/IP — far above our
 * manual-check usage).
 */
class GithubUpdateRepository @Inject constructor(
    private val client: OkHttpClient,
    @ApplicationContext private val context: Context,
) : UpdateRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun checkForUpdate(): UpdateCheckResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(LATEST_RELEASE_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "OneAsmr/${BuildConfig.VERSION_NAME}")
            .build()
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            return@withContext UpdateCheckResult.Error("网络连接失败，请稍后重试")
        }
        response.use { resp ->
            if (resp.code == 403 || resp.code == 429) {
                return@withContext UpdateCheckResult.Error("更新服务器限流（HTTP ${resp.code}），请稍后重试")
            }
            if (!resp.isSuccessful) {
                return@withContext UpdateCheckResult.Error("更新服务器响应异常（HTTP ${resp.code}）")
            }
            val body = resp.body
            val release = try {
                json.decodeFromString<GithubRelease>(body.string())
            } catch (e: Exception) {
                return@withContext UpdateCheckResult.Error("更新数据解析失败")
            }
            val remoteVersion = AppVersion.parse(release.tagName)
                ?: return@withContext UpdateCheckResult.Error("远端版本号格式无法识别（${release.tagName}）")
            val currentVersion = AppVersion.parse(BuildConfig.VERSION_NAME)
                ?: return@withContext UpdateCheckResult.UpToDate
            if (remoteVersion <= currentVersion) return@withContext UpdateCheckResult.UpToDate
            val asset = release.assets.firstOrNull { it.name.endsWith(".apk") }
                ?: return@withContext UpdateCheckResult.Error("最新版本未附带 APK 安装包")
            UpdateCheckResult.NewVersion(
                UpdateInfo(
                    versionName = release.tagName.removePrefix("v"),
                    downloadUrl = asset.browserDownloadUrl,
                    sizeBytes = asset.size,
                    changelog = release.body.orEmpty().trim(),
                ),
            )
        }
    }

    override suspend fun downloadApk(info: UpdateInfo, onProgress: (Long, Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            val target = File(context.cacheDir, "update/${info.versionName}.apk")
            target.parentFile?.mkdirs()
            val request = Request.Builder().url(info.downloadUrl).build()
            val response = try {
                client.newCall(request).execute()
            } catch (e: IOException) {
                throw IOException("下载失败：${e.message}", e)
            }
            response.use { resp ->
                if (!resp.isSuccessful) throw IOException("下载失败：HTTP ${resp.code}")
                val body = resp.body
                val total = body.contentLength()
                var downloaded = 0L
                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            onProgress(downloaded, total)
                        }
                    }
                }
            }
            target
        }

    private companion object {
        const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/HentaiMikuy/OneAsmr/releases/latest"
        const val BUFFER_SIZE = 64 * 1024
    }
}
