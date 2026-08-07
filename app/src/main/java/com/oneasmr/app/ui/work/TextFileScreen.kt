package com.oneasmr.app.ui.work

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Built-in text viewer (plan Task 14). Auto-detects the encoding with
 * juniversalchardet; when detection/decode verification fails it shows the
 * encoding-switch menu INSTEAD of mojibake — [TextFileUiState.showEncodingMenu]
 * gates the text rendering, so unverified text never appears.
 */
@Composable
fun TextFileScreen(viewModel: TextFileViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "文本查看器",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        EncodingBar(state = state, onSelect = viewModel::selectEncoding, onRetry = viewModel::retryAutoDetect)
        Spacer(Modifier.height(8.dp))
        when {
            state.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text("正在读取并探测编码…", style = MaterialTheme.typography.bodyMedium)
            }
            state.error != null -> Text(
                state.error!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            state.showEncodingMenu -> EncodingMenuPrompt(state = state, onSelect = viewModel::selectEncoding, onRetry = viewModel::retryAutoDetect)
            state.text != null -> Text(
                state.text!!,
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun EncodingBar(
    state: TextFileUiState,
    onSelect: (String) -> Unit,
    onRetry: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "编码：${state.selectedEncoding ?: state.detectedEncoding ?: "未知"}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onRetry, enabled = !state.loading) { Text("自动探测") }
        EncodingDropdown(state = state, onSelect = onSelect)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EncodingDropdown(state: TextFileUiState, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = state.selectedEncoding ?: "选择编码",
            onValueChange = {},
            readOnly = true,
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryEditable).width(150.dp),
            textStyle = MaterialTheme.typography.bodySmall,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            state.encodings.forEach { name ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        expanded = false
                        onSelect(name)
                    },
                )
            }
        }
    }
}

@Composable
private fun EncodingMenuPrompt(
    state: TextFileUiState,
    onSelect: (String) -> Unit,
    onRetry: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("无法可靠识别文件编码", style = MaterialTheme.typography.titleSmall)
            state.warning?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(12.dp))
            Text("请选择一种编码（可选择后重新自动探测）：", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            EncodingDropdown(state = state, onSelect = onSelect)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onRetry) { Text("重新自动探测") }
        }
    }
}
