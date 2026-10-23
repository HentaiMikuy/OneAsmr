package com.oneasmr.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oneasmr.app.BuildConfig
import com.oneasmr.app.data.update.AppVersion
import com.oneasmr.app.data.update.UpdateCache
import com.oneasmr.app.data.update.UpdateCheckResult
import com.oneasmr.app.data.update.UpdateDownloader
import com.oneasmr.app.data.update.UpdateDownloadState
import com.oneasmr.app.data.update.UpdateInfo
import com.oneasmr.app.data.update.UpdateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** UI state machine for the Settings-screen update row. */
sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Restoring : UpdateUiState
    data object Checking : UpdateUiState

    /**
     * The check completed and the installed build is already the latest one.
     * This used to map straight back to [Idle] with no feedback at all; it now
     * exists so the screen can surface a one-shot "已是最新版本" toast (the
     * caller then acknowledges it via [UpdateViewModel.dismissUpToDate]).
     */
    data object UpToDate : UpdateUiState
    data class Available(val info: UpdateInfo) : UpdateUiState
    data class Downloading(
        val info: UpdateInfo,
        val downloaded: Long,
        val total: Long,
        val message: String? = null,
    ) : UpdateUiState
    data class Downloaded(val info: UpdateInfo, val file: File) : UpdateUiState
    data class Failed(val message: String, val info: UpdateInfo? = null) : UpdateUiState
}

@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val updateRepository: UpdateRepository,
    private val updateCache: UpdateCache,
    private val updateDownloader: UpdateDownloader,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Restoring)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // Poll only while the settings UI observes progress. The system transfer continues independently.
            _state.subscriptionCount.map { it > 0 }.distinctUntilChanged().collectLatest { observed ->
                if (!observed) return@collectLatest
                updateDownloader.observe().collect { download ->
                    when (download) {
                        UpdateDownloadState.None -> {
                            if (_state.value is UpdateUiState.Restoring ||
                                _state.value is UpdateUiState.Downloaded ||
                                _state.value is UpdateUiState.Downloading
                            ) {
                                _state.value = cachedUpdate()?.let { UpdateUiState.Available(it) } ?: UpdateUiState.Idle
                            }
                        }
                        is UpdateDownloadState.Downloading -> _state.value = UpdateUiState.Downloading(
                            download.info, download.downloaded, download.total, download.message,
                        )
                        is UpdateDownloadState.Downloaded -> _state.value = UpdateUiState.Downloaded(download.info, download.file)
                        is UpdateDownloadState.Failed -> _state.value = UpdateUiState.Failed(download.message, download.info)
                    }
                }
            }
        }
        // Seed from the (already-running) app-startup check; only surfaces a
        // found version, mirror state stays untouched.
        viewModelScope.launch {
            updateCache.result.collect {
                val info = cachedUpdate() ?: return@collect
                if (_state.value is UpdateUiState.Idle) {
                    _state.value = UpdateUiState.Available(info)
                }
            }
        }
    }

    fun checkForUpdate() {
        if (_state.value is UpdateUiState.Restoring || _state.value is UpdateUiState.Checking ||
            _state.value is UpdateUiState.Downloading || _state.value is UpdateUiState.Downloaded
        ) return
        _state.value = UpdateUiState.Checking
        viewModelScope.launch {
            updateRepository.checkForUpdate().let { result ->
                if (_state.value !is UpdateUiState.Checking) return@launch
                _state.value = when (result) {
                    is UpdateCheckResult.NewVersion -> UpdateUiState.Available(result.info)
                    UpdateCheckResult.UpToDate -> UpdateUiState.UpToDate
                    is UpdateCheckResult.Error -> UpdateUiState.Failed(result.message)
                }
            }
        }
    }

    fun retryAfterError() {
        val failed = _state.value as? UpdateUiState.Failed ?: return
        val info = failed.info
        if (info != null) startDownload(info) else checkForUpdate()
    }

    fun dismissError() {
        if (_state.value is UpdateUiState.Failed) _state.value = UpdateUiState.Idle
    }

    /** Acknowledges the "已是最新版本" result once the toast has been shown. */
    fun dismissUpToDate() {
        if (_state.value is UpdateUiState.UpToDate) _state.value = UpdateUiState.Idle
    }

    fun download() {
        val current = _state.value as? UpdateUiState.Available ?: return
        startDownload(current.info)
    }

    private fun startDownload(info: UpdateInfo) {
        _state.value = UpdateUiState.Downloading(info, 0, info.sizeBytes)
        viewModelScope.launch {
            try {
                updateDownloader.enqueue(info)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UpdateUiState.Failed(e.message ?: "无法启动系统下载，请重试", info)
            }
        }
    }

    fun refreshDownloadState() {
        updateDownloader.refresh()
    }

    private fun cachedUpdate(): UpdateInfo? {
        val info = (updateCache.result.value as? UpdateCheckResult.NewVersion)?.info ?: return null
        val remote = AppVersion.parse(info.versionName) ?: return null
        val installed = AppVersion.parse(BuildConfig.VERSION_NAME)
        return info.takeIf { installed == null || remote > installed }
    }

    /** Public API available on every supported Android version (minSdk 26). */
    fun canRequestInstallPermission(): Boolean = appContext.packageManager.canRequestPackageInstalls()
}
