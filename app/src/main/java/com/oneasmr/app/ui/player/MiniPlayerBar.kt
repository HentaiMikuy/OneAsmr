package com.oneasmr.app.ui.player

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oneasmr.app.data.repository.CoverStore
import com.oneasmr.app.data.repository.CoverType
import com.oneasmr.app.ui.common.CoverImage

private const val TAG = "MiniPlayerBar"

/**
 * Global mini player bar (plan Task 21): shown above the library/search/etc.
 * screens whenever a media session with content is active.
 *
 * - Visibility is derived from the session connection ([PlayerSnapshot.hasSession])
 *   — the controller binds the session (auto-reconnects on service restart),
 *   so force-stop mid-play + relaunch shows the restored session, and
 *   swipe-to-dismiss / error-stops hide it via the same channel. No
 *   app-local session state exists anywhere.
 * - Tap -> full player page (route carries the session's CURRENT item, so the
 *   page attaches without rebuilding the queue — plan must-not).
 * - Swipe down/right -> dismiss = stop playback ([MiniPlayerViewModel.stopPlayback]).
 * - Playback failure (work folder deleted mid-play) -> one toast, then the
 *   session goes idle (service clears + stops) and the bar disappears.
 */
@Composable
fun MiniPlayerBarHost(
    onOpenPlayer: (workId: String, trackIndex: Int) -> Unit,
    viewModel: MiniPlayerViewModel = hiltViewModel(),
) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val coverStore = rememberMiniCoverStore()

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            if (event is PlayerEvent.PlaybackFailed) {
                Log.i(TAG, "playback failed event -> toast: ${event.message}")
                Toast.makeText(context, "播放失败：${event.message}，已停止", Toast.LENGTH_LONG).show()
            }
        }
    }

    if (!snapshot.hasSession) return

    val onTap: () -> Unit = {
        val workId = snapshot.workId
        val trackIndex = snapshot.trackIndex
        if (workId != null && trackIndex != null) {
            onOpenPlayer(workId, trackIndex)
        }
    }

    MiniPlayerBar(
        snapshot = snapshot,
        coverStore = coverStore,
        onTap = onTap,
        onTogglePlayPause = viewModel::togglePlayPause,
        onStop = viewModel::stopPlayback,
    )
}

@Composable
private fun MiniPlayerBar(
    snapshot: PlayerSnapshot,
    coverStore: CoverStore,
    onTap: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onStop: () -> Unit,
) {
    // Swipe-to-dismiss (horizontal, EndToStart — the documented equivalent of
    // a vertical swipe on this Material3 version): stops playback, which
    // clears the session queue so the bar leaves composition.
    val dismissState = rememberSwipeToDismissBoxState()
    val dismissed = dismissState.currentValue
    LaunchedEffect(dismissed) {
        if (dismissed == SwipeToDismissBoxValue.EndToStart) {
            onStop()
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(MaterialTheme.colorScheme.errorContainer),
            )
        },
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp),
            tonalElevation = 3.dp,
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clickable(onClick = onTap)
                    .semantics { contentDescription = "mini player ${snapshot.trackTitle} playing=${snapshot.isPlaying}" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                snapshot.rjCode?.let { rjCode ->
                    CoverImage(
                        coverStore = coverStore,
                        rjCode = rjCode,
                        type = CoverType.MAIN,
                        rootFolderUri = null,
                        relativeDir = null,
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .size(40.dp)
                            .clip(RoundedCornerShape(6.dp)),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        snapshot.trackTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        snapshot.workTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(
                    onClick = onTogglePlayPause,
                    modifier = Modifier.semantics {
                        contentDescription = if (snapshot.isPlaying) "mini pause" else "mini play"
                    },
                ) {
                    Icon(
                        if (snapshot.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = null,
                    )
                }
            }
        }
    }
}

/** Hilt EntryPoint for the CoverStore inside the bottom-bar mini player. */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface MiniPlayerCoverEntryPoint {
    fun coverStore(): CoverStore
}

@Composable
private fun rememberMiniCoverStore(): CoverStore {
    val context = LocalContext.current
    return remember {
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            context.applicationContext,
            MiniPlayerCoverEntryPoint::class.java,
        ).coverStore()
    }
}
