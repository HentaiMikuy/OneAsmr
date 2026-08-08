package com.oneasmr.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import com.oneasmr.app.data.local.settings.ServerConfig

/**
 * Server mode management + JWT login (plan Task 24).
 *
 * Per-server card: name/baseUrl/active radio + phase line; 连接 button runs
 * the probe (GET /api/health + /api/auth/me); auth-enabled servers then show
 * the login form inline (name/password -> POST /api/auth/me -> encrypted
 * token); auth-disabled servers connect directly. 退出登录 clears the token;
 * 编辑/删除 manage the config. The message banner is the failure channel
 * (wrong password 401 / unreachable server — no crashes, clear text).
 */
@Composable
fun ServerLoginScreen(
    onBack: () -> Unit = {},
    viewModel: ServerLoginViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var editing by remember { mutableStateOf<ServerConfig?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<ServerConfig?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("返回") }
            Spacer(Modifier.width(8.dp))
            Text("服务器模式", style = MaterialTheme.typography.headlineSmall)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "配置 kikoeru-express 服务器并登录。支持多服务器，令牌加密保存，可随时切换。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        if (uiState.message != null) {
            Text(
                text = uiState.message.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = viewModel::consumeMessage)
                    .padding(vertical = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
        }

        if (uiState.servers.isEmpty()) {
            Text(
                "尚未配置服务器 — 点击下方按钮添加。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
        } else {
            uiState.servers.forEach { server ->
                ServerCard(
                    server = server,
                    entry = uiState.entry(server.id),
                    isActive = server.id == uiState.activeServerId,
                    onSelect = { viewModel.setActive(server.id) },
                    onTest = { viewModel.test(server.id) },
                    onLogin = { name, password -> viewModel.login(server.id, name, password) },
                    onLogout = { viewModel.logout(server.id) },
                    onEdit = { editing = server },
                    onRemove = { removeTarget = server },
                )
                Spacer(Modifier.height(12.dp))
            }
        }

        OutlinedButton(
            onClick = { showAdd = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("添加服务器")
        }
    }

    if (showAdd) {
        ServerEditorDialog(
            title = "添加服务器",
            initial = null,
            onDismiss = { showAdd = false },
            onSave = { name, baseUrl ->
                viewModel.addServer(name, baseUrl)
                showAdd = false
            },
        )
    }
    if (editing != null) {
        ServerEditorDialog(
            title = "编辑服务器",
            initial = editing,
            onDismiss = { editing = null },
            onSave = { name, baseUrl ->
                editing?.let { viewModel.updateServer(it.id, name, baseUrl) }
                editing = null
            },
        )
    }
    if (removeTarget != null) {
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("删除服务器") },
            text = { Text("确定删除「${removeTarget?.name}」？已保存的登录令牌也会一并清除。") },
            confirmButton = {
                TextButton(onClick = {
                    removeTarget?.let { viewModel.removeServer(it.id) }
                    removeTarget = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ServerCard(
    server: ServerConfig,
    entry: ServerEntryUi,
    isActive: Boolean,
    onSelect: () -> Unit,
    onTest: () -> Unit,
    onLogin: (String, String) -> Unit,
    onLogout: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    val phase = entry.phase
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = isActive, onClick = onSelect)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        server.name,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isActive) {
                        Text(
                            "当前",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    server.baseUrl,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        StatusLine(phase, entry.tokenStored)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (phase) {
                is ServerPhase.Testing -> Text(
                    "连接测试中…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                is ServerPhase.LoggingIn -> Text(
                    "登录中…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> {
                    Button(onClick = onTest, enabled = phase !is ServerPhase.Testing && phase !is ServerPhase.LoggingIn) {
                        Text("连接")
                    }
                    if (phase is ServerPhase.Connected) {
                        OutlinedButton(onClick = onLogout) { Text("退出登录") }
                    }
                    TextButton(onClick = onEdit) { Text("编辑") }
                    TextButton(onClick = onRemove) { Text("删除") }
                }
            }
        }
        if (phase is ServerPhase.NeedLogin) {
            LoginForm(server, phase, onLogin)
        }
        HorizontalDivider(Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun StatusLine(phase: ServerPhase, tokenStored: Boolean) {
    val (text, color) = when (phase) {
        ServerPhase.Idle -> "未连接" to MaterialTheme.colorScheme.onSurfaceVariant
        ServerPhase.Testing, ServerPhase.LoggingIn -> "连接中…" to MaterialTheme.colorScheme.onSurfaceVariant
        is ServerPhase.NeedLogin -> {
            val hint = phase.userHint?.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
            "需要登录$hint" to MaterialTheme.colorScheme.tertiary
        }
        is ServerPhase.Connected -> {
            val mode = if (phase.authEnabled) "已登录（${phase.user}）" else "已连接（免登录）"
            mode to MaterialTheme.colorScheme.primary
        }
        is ServerPhase.Failed -> phase.message to MaterialTheme.colorScheme.error
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (phase is ServerPhase.Testing || phase is ServerPhase.LoggingIn) {
            CircularProgressIndicator(
                modifier = Modifier.width(14.dp).height(14.dp),
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color, maxLines = 2)
        if (tokenStored && phase !is ServerPhase.Connected) {
            Spacer(Modifier.width(6.dp))
            Text(
                "令牌已保存",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LoginForm(server: ServerConfig, phase: ServerPhase.NeedLogin, onLogin: (String, String) -> Unit) {
    var name by remember(server.id) { mutableStateOf("") }
    var password by remember(server.id) { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(start = 8.dp)) {
        Text(
            "服务器开启了登录认证（kikoeru 账号）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("用户名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("密码") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = { onLogin(name.trim(), password) }, enabled = name.isNotBlank() && password.isNotBlank()) {
            Text("登录")
        }
    }
}

@Composable
private fun ServerEditorDialog(
    title: String,
    initial: ServerConfig?,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var name by remember(initial?.id) { mutableStateOf(initial?.name.orEmpty()) }
    var baseUrl by remember(initial?.id) { mutableStateOf(initial?.baseUrl.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("服务器地址（如 http://192.168.1.5:9527）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim(), baseUrl.trim()) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
