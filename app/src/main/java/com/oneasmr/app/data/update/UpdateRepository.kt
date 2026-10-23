package com.oneasmr.app.data.update

import com.oneasmr.app.BuildConfig
import javax.inject.Inject
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Queries the GitHub "latest release" API. APK transfers are managed
 * independently by [UpdateDownloader], so they outlive the settings screen.
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
}

/**
 * GitHub-backed implementation. Uses the public latest-release API of this
 * repository (no auth; unauthenticated rate limit 60 req/h/IP — far above our
 * manual-check usage).
 */
class GithubUpdateRepository @Inject constructor(
    private val client: OkHttpClient,
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
                    sha256 = asset.digest?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:"),
                ),
            )
        }
    }

    private companion object {
        const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/HentaiMikuy/OneAsmr/releases/latest"
    }
}
