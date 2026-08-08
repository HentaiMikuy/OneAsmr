package com.oneasmr.app.data.local.settings

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.oneasmr.app.domain.player.PlayQueueItem
import com.oneasmr.app.domain.player.PlaybackSpeed
import com.oneasmr.app.domain.player.RepeatMode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The last playback session's queue structure + modes (plan Task 18).
 *
 * Persisted in a dedicated DataStore file ("queue_state") following the
 * ScanRootsStore pattern: the queue itself is ONE JSON blob (kotlinx
 * serialization, `ignoreUnknownKeys` so old blobs stay readable across
 * upgrades — stale_state class), while repeat/shuffle/speed are individual
 * typed preference keys with explicit fallbacks — a future enum value can
 * never corrupt the whole persisted session.
 *
 * Scope boundary (plan Task 18/19): THIS store restores the queue structure
 * and the playback modes on cold start. The playback POSITION (positionMs)
 * is Task 19's playback_state table — Task 19 will read it at restore time
 * and feed `startPositionMs`; Task 18 restores with position 0.
 */
@Serializable
data class PersistedQueue(
    val workId: String,
    val items: List<PlayQueueItem>,
    /** Index into [items] the session was on when last saved. */
    val currentIndex: Int,
) {
    /** True when a cold start can restore anything (has at least one item). */
    val isRestorable: Boolean get() = items.isNotEmpty() && currentIndex in items.indices
}

/** DataStore-backed persistence for the playback session state. */
@Singleton
class QueueStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    /** Last queue (null when none was saved / corrupt / cleared). */
    val queue: Flow<PersistedQueue?> = dataStore.data.map { prefs ->
        val raw = prefs[KEY_QUEUE_JSON] ?: return@map null
        runCatching { json.decodeFromString<PersistedQueue>(raw) }.getOrElse {
            Log.w(TAG, "queue_state JSON corrupt; treating as empty", it)
            null
        }
    }

    val repeatMode: Flow<RepeatMode> =
        dataStore.data.map { RepeatMode.fromStored(it[KEY_REPEAT_MODE]) }

    val shuffleEnabled: Flow<Boolean> =
        dataStore.data.map { it[KEY_SHUFFLE_ENABLED] ?: false }

    val speed: Flow<Float> =
        dataStore.data.map { it[KEY_SPEED]?.let(PlaybackSpeed::normalize) ?: 1f }

    /** Saves the queue structure; null clears it (queue replaced/emptied). */
    suspend fun saveQueue(queue: PersistedQueue?) {
        dataStore.edit {
            if (queue == null) it.remove(KEY_QUEUE_JSON)
            else it[KEY_QUEUE_JSON] = json.encodeToString(queue)
        }
    }

    suspend fun setRepeatMode(mode: RepeatMode) {
        dataStore.edit { it[KEY_REPEAT_MODE] = RepeatMode.toStored(mode) }
    }

    suspend fun setShuffleEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_SHUFFLE_ENABLED] = enabled }
    }

    suspend fun setSpeed(speed: Float) {
        dataStore.edit { it[KEY_SPEED] = PlaybackSpeed.normalize(speed) }
    }

    companion object {
        private const val TAG = "OneAsmrQueueStore"
        private val KEY_QUEUE_JSON = stringPreferencesKey("queue_json")
        private val KEY_REPEAT_MODE = intPreferencesKey("repeat_mode")
        private val KEY_SHUFFLE_ENABLED = booleanPreferencesKey("shuffle_enabled")
        private val KEY_SPEED = floatPreferencesKey("playback_speed")

        // ignoreUnknownKeys keeps old JSON blobs readable when new fields land
        // in a future build installed over the previous APK (stale_state class).
        private val json = Json { ignoreUnknownKeys = true }
    }
}
