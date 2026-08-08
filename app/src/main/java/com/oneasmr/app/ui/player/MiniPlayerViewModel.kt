package com.oneasmr.app.ui.player

import androidx.lifecycle.ViewModel
import com.oneasmr.app.player.SessionConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Mini player bar state (plan Task 21): a thin projection of the shared
 * [SessionConnection] — visibility and every field come from the media
 * session connection (single source of truth), never from app-local state.
 * The queue is never built here (must-not): the session owns it.
 */
@HiltViewModel
class MiniPlayerViewModel @Inject constructor(
    private val sessionConnection: SessionConnection,
) : ViewModel() {

    val snapshot: StateFlow<PlayerSnapshot> = sessionConnection.snapshot

    /** One-shot events (playback failure -> toast). */
    val events: SharedFlow<PlayerEvent> = sessionConnection.events

    /** Mini-bar play/pause toggle -> session command. */
    fun togglePlayPause() = sessionConnection.togglePlayPause()

    /** Mini-bar swipe-to-dismiss -> stop playback (pause + clear session queue). */
    fun stopPlayback() = sessionConnection.stopPlayback()
}
