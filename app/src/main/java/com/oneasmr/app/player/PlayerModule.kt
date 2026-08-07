package com.oneasmr.app.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt wiring for the playback core (plan Task 17).
 *
 * The ExoPlayer is a process singleton so the MediaSessionService and the
 * player UI (Task 21) share ONE player instance — the app never holds the
 * player inside an Activity (plan must-not). The service keeps the player
 * alive across session rebuilds, which is what makes in-process playback
 * resumption work after `stopService`.
 *
 * Focus/noisy/wake-mode are configured HERE, at the single construction
 * point, per plan Task 17:
 * - [AudioAttributes] usage MEDIA + [ExoPlayer.Builder.setAudioAttributes]
 *   with `handleAudioFocus = true` → automatic pause on audio-focus loss.
 * - [ExoPlayer.Builder.setHandleAudioBecomingNoisy] → pause on headphone
 *   unplug / ACTION_AUDIO_BECOMING_NOISY.
 * - [ExoPlayer.setWakeMode] WAKE_MODE_LOCAL → keeps decoding with the
 *   screen off (WAKE_LOCK permission declared in the manifest).
 */
@Module
@InstallIn(SingletonComponent::class)
object PlayerModule {

    @Provides
    @Singleton
    fun provideExoPlayer(@ApplicationContext context: Context): ExoPlayer =
        ExoPlayer.Builder(context)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus= */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply { setWakeMode(C.WAKE_MODE_LOCAL) }
}
