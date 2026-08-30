package com.oneasmr.app.ui.work

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oneasmr.app.data.text.DecodeFailure
import com.oneasmr.app.data.text.TextDecoding
import com.oneasmr.app.data.text.TextFileReader
import com.oneasmr.app.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Built-in text viewer state (plan Task 14: "text 节点→内置文本查看器（编码自动
 * 探测，UTF-8/Shift_JIS 等）" + "必须处理乱码（探测失败时给编码切换菜单）").
 *
 * The auto path NEVER renders unverified text:
 * - no confident detection (or empty file) → [TextFileUiState.showEncodingMenu]
 *   = true, text = null — the UI must show the manual menu, NOT mojibake;
 * - a detected charset that decodes but fails byte round-trip → same menu
 *   state (the detection is untrustworthy);
 * - strict decode failure → warning + menu.
 *
 * The manual menu is always reachable ([selectEncoding]); a selection that
 * verifies replaces the text, one that fails keeps the menu with a warning.
 */
data class TextFileUiState(
    val loading: Boolean = true,
    val text: String? = null,
    val detectedEncoding: String? = null,
    val selectedEncoding: String? = null,
    val showEncodingMenu: Boolean = false,
    val warning: String? = null,
    val error: String? = null,
) {
    val encodings: List<String> get() = TextDecoding.CANDIDATES
}

@HiltViewModel
class TextFileViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val reader: TextFileReader,
) : ViewModel() {

    private val documentUri: String = checkNotNull(savedStateHandle[Routes.TEXT_VIEWER_ARG_URI])

    private val _uiState = MutableStateFlow(TextFileUiState())
    val uiState: StateFlow<TextFileUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        _uiState.value = TextFileUiState()
        viewModelScope.launch {
            val bytes = try {
                reader.read(documentUri)
            } catch (e: IOException) {
                _uiState.update { it.copy(loading = false, error = e.message ?: "无法读取文件") }
                return@launch
            }
            // 检测/校验是 CPU 密集(最多 4MB 统计检测 + 解码 + 往返校验),丢到 Default。
            val detected = withContext(Dispatchers.Default) { TextDecoding.detectEncoding(bytes) }
            if (detected == null) {
                _uiState.update {
                    it.copy(
                        loading = false,
                        detectedEncoding = null,
                        showEncodingMenu = true,
                        warning = "无法自动识别文件编码，请手动选择（不会直接显示乱码）",
                    )
                }
                return@launch
            }
            val decoded = try {
                withContext(Dispatchers.Default) { TextDecoding.decodeAndVerify(bytes, detected) }
            } catch (e: DecodeFailure) {
                _uiState.update {
                    it.copy(
                        loading = false,
                        detectedEncoding = detected,
                        showEncodingMenu = true,
                        warning = "自动识别为 $detected，但解码校验失败，请手动选择编码",
                    )
                }
                return@launch
            }
            if (!decoded.verified) {
                _uiState.update {
                    it.copy(
                        loading = false,
                        detectedEncoding = detected,
                        showEncodingMenu = true,
                        warning = "自动识别为 $detected，但内容校验失败，请手动选择编码",
                    )
                }
                return@launch
            }
            _uiState.update {
                it.copy(
                    loading = false,
                    text = decoded.text,
                    detectedEncoding = detected,
                    selectedEncoding = detected,
                )
            }
        }
    }

    /** Manual encoding switch; keeps the menu open until the selection verifies. */
    fun selectEncoding(charsetName: String) {
        viewModelScope.launch {
            val bytes = try {
                reader.read(documentUri)
            } catch (e: IOException) {
                _uiState.update { it.copy(error = e.message ?: "无法读取文件") }
                return@launch
            }
            val decoded = try {
                withContext(Dispatchers.Default) { TextDecoding.decodeAndVerify(bytes, charsetName) }
            } catch (e: DecodeFailure) {
                _uiState.update {
                    it.copy(
                        text = null,
                        selectedEncoding = charsetName,
                        showEncodingMenu = true,
                        warning = "编码 $charsetName 无法解码该文件，请换一种",
                    )
                }
                return@launch
            }
            if (decoded.verified) {
                _uiState.update {
                    it.copy(
                        text = decoded.text,
                        selectedEncoding = charsetName,
                        detectedEncoding = charsetName,
                        showEncodingMenu = false,
                        warning = null,
                        error = null,
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        text = null,
                        selectedEncoding = charsetName,
                        showEncodingMenu = true,
                        warning = "编码 $charsetName 解码结果与文件内容不符，可能仍有乱码，请换一种",
                    )
                }
            }
        }
    }

    /** Re-runs auto-detection after manual attempts failed. */
    fun retryAutoDetect() {
        load()
    }
}
