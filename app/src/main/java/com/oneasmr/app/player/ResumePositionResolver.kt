package com.oneasmr.app.player

import android.util.Log
import com.oneasmr.app.data.local.PlaybackStateDao
import com.oneasmr.app.data.local.settings.ResumeMode
import com.oneasmr.app.data.local.settings.SettingsStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Resolves the start position for one trackKey from playback_state (plan
 * Task 19): applies [ResumePolicy] and the user's [ResumeMode] setting.
 *
 * - Unknown key or no row -> 0 (fresh start).
 * - Policy says START_OVER (never-started <3% or fully-listened >95%) -> 0.
 * - Policy says RESUME -> the remembered position, clamped to the duration.
 * - [ResumeMode.ALWAYS_ASK]: the ask-UI lands with the Task 21 player screen;
 *   until then the mode logs its intent and auto-resumes (documented
 *   placeholder — the setting ships in Task 27's full settings page).
 *
 * Shared by [PlaybackService] (cold-start restore + media-button resumption)
 * and [com.oneasmr.app.ui.player.PlayerViewModel] (the detail-page play
 * entry) so every start path resolves identically.
 */
@Singleton
class ResumePositionResolver @Inject constructor(
    private val dao: PlaybackStateDao,
    private val settingsStore: SettingsStore,
) {

    suspend fun resolve(trackKey: String?): Long {
        if (trackKey == null) return 0L
        val state = dao.get(trackKey) ?: return 0L
        if (ResumePolicy.decide(state.positionMs, state.durationMs) != ResumePolicy.Decision.RESUME) {
            return 0L
        }
        if (settingsStore.resumeMode.first() == ResumeMode.ALWAYS_ASK) {
            // Ask-dialog lands in Task 21 (player UI); auto-resume today so
            // the setting never silently stops resuming (documented behavior).
            Log.i(TAG, "resume mode ALWAYS_ASK: auto-resuming $trackKey (ask UI = Task 21)")
        }
        val clamped = state.positionMs.coerceIn(0L, state.durationMs)
        Log.i(
            TAG,
            "resume resolved: key=$trackKey pos=${clamped}ms " +
                "(stored ${state.positionMs}ms/${state.durationMs}ms, " +
                "policy=${ResumePolicy.decide(state.positionMs, state.durationMs)})",
        )
        return clamped
    }

    private companion object {
        const val TAG = "ResumePosition"
    }
}
