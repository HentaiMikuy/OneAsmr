package com.oneasmr.app.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oneasmr.app.data.local.KeySpec
import com.oneasmr.app.data.local.isCensored
import com.oneasmr.app.data.local.WorkDao
import com.oneasmr.app.data.local.settings.SettingsStore
import com.oneasmr.app.player.SessionConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Mini player bar state (plan Task 21): a thin projection of the shared
 * [SessionConnection] — visibility and every field come from the media
 * session connection (single source of truth), never from app-local state.
 * The queue is never built here (must-not): the session owns it.
 *
 * [censored] is the one addition: the current work's age rating (Room flow)
 * combined with the persisted NSFW switch decides whether the mini bar's
 * cover thumb is hidden in safe mode. Room re-emits on rating changes, so
 * setting a rating on the detail page re-evaluates live.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MiniPlayerViewModel @Inject constructor(
    private val sessionConnection: SessionConnection,
    private val workDao: WorkDao,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    val snapshot: StateFlow<PlayerSnapshot> = sessionConnection.snapshot

    /**
     * 安全模式:当前作品封面应和谐。rjCode 或作品行未知时按敏感处理
     * (安全第一);评级变化经 Room 流自动重估。
     */
    val censored: StateFlow<Boolean> = combine(
        snapshot.map { it.rjCode },
        settingsStore.nsfwEnabled,
    ) { rjCode, enabled -> rjCode to enabled }
        .flatMapLatest { (rjCode, enabled) ->
            if (rjCode == null) {
                // 无当前曲目:NSFW 关即按敏感处理。
                flowOf(!enabled)
            } else {
                workDao.getByIdFlow(KeySpec.workId(KeySpec.LOCAL_SOURCE, rjCode))
                    .map { work -> !enabled && work?.ageRating.isCensored() }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** One-shot events (playback failure -> toast). */
    val events: SharedFlow<PlayerEvent> = sessionConnection.events

    /** Mini-bar play/pause toggle -> session command. */
    fun togglePlayPause() = sessionConnection.togglePlayPause()

    /** Mini-bar swipe-to-dismiss -> stop playback (pause + clear session queue). */
    fun stopPlayback() = sessionConnection.stopPlayback()
}
