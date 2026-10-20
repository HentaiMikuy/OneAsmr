package com.oneasmr.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oneasmr.app.data.update.UpdateCache
import com.oneasmr.app.data.update.UpdateCheckResult
import com.oneasmr.app.data.update.UpdateInfo
import com.oneasmr.app.data.update.UpdateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** UI state machine for the Settings-screen update row. */
sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class Available(val info: UpdateInfo) : UpdateUiState
    data class Downloading(val info: UpdateInfo, val downloaded: Long, val total: Long) : UpdateUiState
    data class Downloaded(val info: UpdateInfo, val file: File) : UpdateUiState
    data class Failed(val message: String) : UpdateUiState
}

@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val updateRepository: UpdateRepository,
    private val updateCache: UpdateCache,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    init {
        // Seed from the (already-running) app-startup check; only surfaces a
        // found version, mirror state stays untouched.
        viewModelScope.launch {
            updateCache.result.collect { result ->
                val info = (result as? UpdateCheckResult.NewVersion)?.info ?: return@collect
                if (_state.value is UpdateUiState.Idle) {
                    _state.value = UpdateUiState.Available(info)
                }
            }
        }
    }

    fun checkForUpdate() {
        if (_state.value is UpdateUiState.Checking || _state.value is UpdateUiState.Downloading) return
        _state.value = UpdateUiState.Checking
        viewModelScope.launch {
            updateRepository.checkForUpdate().let { result ->
                _state.value = when (result) {
                    is UpdateCheckResult.NewVersion -> UpdateUiState.Available(result.info)
                    UpdateCheckResult.UpToDate -> UpdateUiState.Idle
                    is UpdateCheckResult.Error -> UpdateUiState.Failed(result.message)
                }
            }
        }
    }

    fun retryAfterError() {
        if (_state.value is UpdateUiState.Failed) checkForUpdate()
    }

    fun dismissError() {
        if (_state.value is UpdateUiState.Failed) _state.value = UpdateUiState.Idle
    }

    fun dismissAvailable() {
        if (_state.value is UpdateUiState.Available) _state.value = UpdateUiState.Idle
    }

    fun download() {
        val current = _state.value as? UpdateUiState.Available ?: return
        _state.value = UpdateUiState.Downloading(current.info, 0, current.info.sizeBytes)
        viewModelScope.launch {
            runCatching {
                updateRepository.downloadApk(current.info) { done, total ->
                    _state.value = UpdateUiState.Downloading(current.info, done, total)
                }
            }.onSuccess { file ->
                _state.value = UpdateUiState.Downloaded(current.info, file)
            }.onFailure { e ->
                _state.value = UpdateUiState.Failed(e.message ?: "下载失败")
            }
        }
    }

    fun dismissDownloaded() {
        if (_state.value is UpdateUiState.Downloaded) _state.value = UpdateUiState.Idle
    }

    /**
     * Whether this device already allows installing APKs from this app.
     *
     * The platform API is {@code PackageManager.canRequestPackageInstalls(String)}
     * (API 26+), but the bundled SDK stubs (android-27/35/37) only expose a
     * parameterless shadow, so we invoke it via reflection and fall back to
     * optimistically allowing the install when the lookup fails (the system
     * installer still shows its own refusal if the grant is missing).
     */
    fun canRequestInstallPermission(): Boolean {
        val pm = appContext.packageManager
        return try {
            pm.javaClass.getMethod("canRequestPackageInstalls", String::class.java)
                .invoke(pm, appContext.packageName) as Boolean
        } catch (e: Exception) {
            true
        }
    }
}
