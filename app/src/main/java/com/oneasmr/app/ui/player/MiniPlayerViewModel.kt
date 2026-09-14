package com.oneasmr.app.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oneasmr.app.data.local.AgeRating
import com.oneasmr.app.data.local.KeySpec
import com.oneasmr.app.data.local.SingleFileDao
import com.oneasmr.app.data.local.SingleFileKind
import com.oneasmr.app.data.local.WorkDao
import com.oneasmr.app.player.SessionConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
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
 * [cover] is the one addition: the pill's 44dp circular thumb is fed the way
 * the matching list screen feeds its own rows, so the pill can only ever show
 * what that list shows for the same item — a work's cover goes through
 * [com.oneasmr.app.ui.common.CoverImage] (CoverStore cache, bundled work-folder
 * cover, shared safe-mode rule), a single-file entry through its own
 * `thumbSource`. This VM therefore only projects the current item's row; it
 * never decides hidden/shown itself. Room re-emits on rating changes, so
 * setting a rating on the detail page re-evaluates the work thumb live.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MiniPlayerViewModel @Inject constructor(
    private val sessionConnection: SessionConnection,
    private val workDao: WorkDao,
    private val singleFileDao: SingleFileDao,
) : ViewModel() {

    val snapshot: StateFlow<PlayerSnapshot> = sessionConnection.snapshot

    /**
     * Thumb inputs for the playing item. [distinctUntilChanged] keeps the Room
     * subscription from being torn down and rebuilt on every unrelated
     * snapshot emission (play/pause, index changes).
     */
    val cover: StateFlow<MiniCover> = snapshot
        .map { it.workId }
        .distinctUntilChanged()
        .flatMapLatest(::coverFlow)
        .stateIn(viewModelScope, SharingStarted.Eagerly, MiniCover.Work())

    /**
     * Current item -> thumb inputs, dispatched on the scope of the normative
     * workId ("{scope}:{rjCode}"): work entries read the work table, single-file
     * entries the single_file table (which owns `thumbSource`). Any other scope
     * — remote works, a work removed from the library, an empty session —
     * falls back to the all-null [MiniCover.Work] default, i.e. an unknown
     * rating and no bundled-cover fallback: safe mode then draws the lock
     * placeholder, exactly like an unrated work in the library grid.
     */
    private fun coverFlow(workId: String?): Flow<MiniCover> {
        val parts = workId?.let(KeySpec::parseWorkId) ?: return flowOf(MiniCover.Work())
        return when (parts.sourceScope) {
            KeySpec.SINGLE_SOURCE -> {
                val fileId = parts.rjCode.toLongOrNull()
                if (fileId == null) {
                    flowOf(MiniCover.Single())
                } else {
                    singleFileDao.getByIdFlow(fileId).map { file ->
                        MiniCover.Single(thumbSource = file?.thumbSource, kind = file?.kind)
                    }
                }
            }
            KeySpec.LOCAL_SOURCE -> workDao
                .getByIdFlow(KeySpec.workId(parts.sourceScope, parts.rjCode))
                .map { work ->
                    MiniCover.Work(
                        ageRating = work?.ageRating,
                        rootFolderUri = work?.rootFolderUri,
                        relativeDir = work?.relativeDir,
                    )
                }
            else -> flowOf(MiniCover.Work())
        }
    }

    /** One-shot events (playback failure -> toast). */
    val events: SharedFlow<PlayerEvent> = sessionConnection.events

    /** Mini-bar play/pause toggle -> session command. */
    fun togglePlayPause() = sessionConnection.togglePlayPause()

    /** Mini-bar swipe-to-dismiss -> stop playback (pause + clear session queue). */
    fun stopPlayback() = sessionConnection.stopPlayback()
}

/**
 * Thumb input of the playing item ([MiniPlayerViewModel.cover]).
 *
 * [Work.ageRating] drives safe-mode masking through the shared
 * [com.oneasmr.app.ui.common.safeModeHidesCover] rule (null = 未标记, so a
 * lock under safe mode — exactly like an unrated work in the library grid).
 * [Work.rootFolderUri]/[Work.relativeDir] are the work-folder locators for
 * CoverStore's bundled-cover fallback, the same pair a library card passes;
 * null means "no work row" and skips that SAF lookup.
 *
 * [Single] carries the single-file entry's own thumb ([SingleFileKind] only
 * picks the placeholder icon when no thumb exists yet). Single files have no
 * age rating and are not a 作品封面, so there is no safe-mode branch here —
 * the Videos list shows the thumb unconditionally and so does the pill.
 */
sealed interface MiniCover {
    data class Work(
        val ageRating: AgeRating? = null,
        val rootFolderUri: String? = null,
        val relativeDir: String? = null,
    ) : MiniCover

    data class Single(
        val thumbSource: String? = null,
        val kind: SingleFileKind? = null,
    ) : MiniCover
}
