package com.oneasmr.app.data.update

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.oneasmr.app.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

sealed interface UpdateDownloadState {
    data object None : UpdateDownloadState
    data class Downloading(
        val info: UpdateInfo,
        val downloaded: Long,
        val total: Long,
        val message: String? = null,
    ) : UpdateDownloadState
    data class Downloaded(val info: UpdateInfo, val file: File) : UpdateDownloadState
    data class Failed(val info: UpdateInfo?, val message: String) : UpdateDownloadState
}

interface UpdateDownloader {
    fun observe(): Flow<UpdateDownloadState>
    suspend fun enqueue(info: UpdateInfo)
    fun refresh()
}

/** DownloadManager owns the transfer; only observing it is tied to the UI lifecycle. */
@Singleton
class SystemUpdateDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
) : UpdateDownloader {
    private val manager: DownloadManager
        get() = context.getSystemService(DownloadManager::class.java)
            ?: throw IOException("系统下载管理器不可用，请在系统设置中启用")
    private val preferences by lazy { context.getSharedPreferences("update_download", Context.MODE_PRIVATE) }
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val changes = MutableStateFlow(0L)
    private var verifiedFile: Triple<Long, Long, Long>? = null

    @Serializable
    private data class SavedDownload(val id: Long, val info: UpdateInfo, val fileName: String)

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observe(): Flow<UpdateDownloadState> = changes.flatMapLatest {
        flow {
            do {
                val state = try {
                    mutex.withLock { readState() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    UpdateDownloadState.Failed(null, e.message ?: "无法读取系统下载任务，请重试")
                }
                emit(state)
                if (state !is UpdateDownloadState.Downloading) break
                delay(1_000)
            } while (true)
        }
    }.flowOn(Dispatchers.IO)

    override fun refresh() {
        changes.update { it + 1 }
    }

    override suspend fun enqueue(info: UpdateInfo) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = readState()
            if (current is UpdateDownloadState.Downloading ||
                (current is UpdateDownloadState.Downloaded && current.info == info)
            ) {
                refresh()
                return@withLock
            }
            load()?.let { discard(it) }
            if (Uri.parse(info.downloadUrl).scheme != "https") throw IOException("更新下载地址必须使用 HTTPS")
            val fileName = "OneAsmr-${UUID.randomUUID()}.apk"
            val request = DownloadManager.Request(Uri.parse(info.downloadUrl))
                .setTitle("OneAsmr v${info.versionName}")
                .setDescription("下载完成后可返回 OneAsmr 安装")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "update/$fileName")
            val id = manager.enqueue(request)
            val saved = SavedDownload(id, info, fileName)
            // Commit before reporting success so a process restart can find the same task.
            if (!preferences.edit().putString(RECORD_KEY, json.encodeToString(saved)).commit()) {
                manager.remove(id)
                throw IOException("无法保存下载任务，请重试")
            }
            verifiedFile = null
            refresh()
        }
    }

    private fun load(): SavedDownload? {
        val encoded = preferences.getString(RECORD_KEY, null) ?: return null
        return try {
            json.decodeFromString<SavedDownload>(encoded).also {
                require(it.fileName == File(it.fileName).name && it.fileName.startsWith("OneAsmr-") && it.fileName.endsWith(".apk"))
            }
        } catch (e: Exception) {
            preferences.edit().remove(RECORD_KEY).commit()
            throw IOException("下载记录已失效，请重新检查更新", e)
        }
    }

    private suspend fun readState(): UpdateDownloadState {
        val saved = load() ?: return UpdateDownloadState.None
        val installed = AppVersion.parse(BuildConfig.VERSION_NAME)
        val remote = AppVersion.parse(saved.info.versionName)
        if (installed != null && remote != null && remote <= installed) {
            discard(saved)
            return UpdateDownloadState.None
        }
        val cursor = manager.query(DownloadManager.Query().setFilterById(saved.id))
            ?: throw IOException("无法查询系统下载任务，请稍后重试")
        cursor.use {
            if (!it.moveToFirst()) return fail(saved, "下载任务已被移除，请重新下载")
            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            val downloaded = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                .takeIf { size -> size > 0 } ?: saved.info.sizeBytes
            return when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val file = target(saved)
                    try {
                        val stamp = Triple(saved.id, file.length(), file.lastModified())
                        if (!file.isFile) throw IOException("安装包已被清理，请重新下载")
                        if (verifiedFile != stamp) {
                            verifyUpdateApk(file, saved.info)
                            verifiedFile = stamp
                        }
                        UpdateDownloadState.Downloaded(saved.info, file)
                    } catch (e: IOException) {
                        fail(saved, e.message ?: "安装包校验失败，请重新下载")
                    }
                }
                DownloadManager.STATUS_FAILED -> fail(saved, downloadFailureMessage(reason))
                else -> UpdateDownloadState.Downloading(
                    saved.info, downloaded.coerceAtLeast(0), total,
                    when (status) {
                        DownloadManager.STATUS_PENDING -> "等待系统开始下载"
                        DownloadManager.STATUS_PAUSED -> when (reason) {
                            DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "等待网络连接，恢复后将继续下载"
                            DownloadManager.PAUSED_WAITING_TO_RETRY -> "网络暂时异常，系统正在等待重试"
                            DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "等待 Wi-Fi 连接"
                            else -> "系统已暂停下载，稍后自动重试"
                        }
                        else -> null
                    },
                )
            }
        }
    }

    private fun target(saved: SavedDownload): File {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw IOException("下载存储空间暂不可用，请稍后重试")
        return File(root, "update/${saved.fileName}")
    }

    private fun discard(saved: SavedDownload) {
        manager.remove(saved.id)
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let {
            File(it, "update/${saved.fileName}").delete()
        }
        preferences.edit().remove(RECORD_KEY).commit()
        verifiedFile = null
    }

    private fun fail(saved: SavedDownload, message: String): UpdateDownloadState.Failed {
        discard(saved)
        return UpdateDownloadState.Failed(saved.info, message)
    }

    private companion object {
        const val RECORD_KEY = "task"
    }
}

/** Only a successful system task with a complete file can be offered for installation. */
internal suspend fun verifyUpdateApk(file: File, info: UpdateInfo) {
    if (!file.isFile || file.length() <= 0 || (info.sizeBytes > 0 && file.length() != info.sizeBytes)) {
        throw IOException("安装包大小不符，请重新下载")
    }
    val expected = info.sha256 ?: return
    if (!expected.matches(Regex("[0-9a-fA-F]{64}"))) throw IOException("更新校验信息无效，请重新检查更新")
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read == -1) break
            digest.update(buffer, 0, read)
        }
    }
    val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    if (!actual.equals(expected, ignoreCase = true)) throw IOException("安装包 SHA-256 校验失败，请重新下载")
}

internal fun downloadFailureMessage(reason: Int): String = when (reason) {
    DownloadManager.ERROR_INSUFFICIENT_SPACE -> "存储空间不足，请释放空间后重试"
    DownloadManager.ERROR_DEVICE_NOT_FOUND -> "下载存储空间不可用，请稍后重试"
    DownloadManager.ERROR_CANNOT_RESUME -> "系统无法继续此前的下载，请重新下载"
    DownloadManager.ERROR_FILE_ERROR, DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "安装包保存失败，请重新下载"
    in 400..599 -> "下载失败：HTTP $reason，请重试"
    else -> "系统下载失败（$reason），请重试"
}
