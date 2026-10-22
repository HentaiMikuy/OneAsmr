package com.oneasmr.app.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.oneasmr.app.ui.common.OneAsmrSeekBar
import com.oneasmr.app.ui.common.OneAsmrSeekBarColors
import com.oneasmr.app.ui.common.OneAsmrTheme
import kotlinx.coroutines.delay

/**
 * Debug-only visual harness for [OneAsmrSeekBar]: renders the player palette
 * over a player-like dark gradient (rest, buffered, scrubbing) and the video
 * palette over black, plus the light-theme surface variant.
 *
 * The scrubbing rows emit a [PressInteraction.Press] into the slider's own
 * [MutableInteractionSource], so the enlarged glowing thumb and the 5dp track
 * can be inspected in a static screenshot without scripting touch input.
 */
class SeekBarPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OneAsmrTheme(darkTheme = true, dynamicColor = false) {
                PreviewScreen()
            }
        }
    }
}

@Composable
private fun PreviewScreen() {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F13))
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Wave G · 进度条预览", style = MaterialTheme.typography.titleLarge)
        Text(
            "上：播放页配色（暗色渐变底）  下：视频页配色（纯黑） / 浅色主题",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        PlayerBackdropCard {
            SeekRow(label = "播放页 · 未播放", value = 0f, buffered = 0f)
            SeekRow(label = "播放页 · 34% / 已缓冲 62%", value = 0.34f, buffered = 0.62f)
            SeekRow(label = "播放页 · 拖动中 58%", value = 0.58f, buffered = 0.88f, scrubbing = true)
        }

        // Reference only: the stock M3 seek row this pass replaces, so the
        // before/after difference stays reviewable from the same screenshot.
        LegacyCard()

        VideoCard {
            SeekRow(label = "视频页 · 拖动中 42%", value = 0.42f, scrubbing = true, video = true)
            SeekRow(label = "视频页 · 静止 76%", value = 0.76f, video = true)
        }

        OneAsmrTheme(darkTheme = false, dynamicColor = false) {
            Surface(
                color = MaterialTheme.colorScheme.background,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("浅色主题 · 34%", style = MaterialTheme.typography.labelSmall)
                    SeekRow(label = null, value = 0.34f, buffered = 0.6f)
                    Text("浅色主题 · 拖动中 70%", style = MaterialTheme.typography.labelSmall)
                    SeekRow(label = null, value = 0.70f, scrubbing = true)
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PlayerBackdropCard(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(listOf(Color(0xFF2A2A34), Color(0xFF14141A))),
                shape = RoundedCornerShape(16.dp),
            )
            .padding(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { content() }
    }
}

@Composable
private fun VideoCard(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Color.Black, shape = RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) { content() }
    }
}

/** The stock M3 seek row (buffered bar + default Slider + times), for comparison. */
@Composable
private fun LegacyCard() {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF1B1B22), shape = RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Column {
            Text(
                "旧样式 · M3 默认 Slider + 2dp 缓冲条",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(
                progress = { 0.62f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                trackColor = Color.Transparent,
            )
            Slider(
                value = 0.34f,
                onValueChange = {},
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                ),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "00:00",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "03:00",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SeekRow(
    label: String?,
    value: Float,
    buffered: Float = 0f,
    scrubbing: Boolean = false,
    video: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    if (scrubbing) {
        LaunchedEffect(Unit) {
            // Let the slider's interaction-source collectors subscribe first:
            // a Press emitted in the same frame would be dropped (no replay).
            delay(300)
            interactionSource.emit(PressInteraction.Press(Offset.Zero))
        }
    }
    var position by remember { mutableFloatStateOf(value) }
    label?.let {
        Text(
            it,
            style = MaterialTheme.typography.labelSmall,
            color = if (video) Color.White.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    OneAsmrSeekBar(
        value = position,
        onValueChange = { position = it },
        valueRange = 0f..1f,
        bufferedFraction = buffered,
        colors = if (video) OneAsmrSeekBarColors.video() else OneAsmrSeekBarColors.player(),
        interactionSource = interactionSource,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "00:00",
            style = MaterialTheme.typography.labelSmall,
            color = if (video) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Text(
            "03:00",
            style = MaterialTheme.typography.labelSmall,
            color = if (video) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
