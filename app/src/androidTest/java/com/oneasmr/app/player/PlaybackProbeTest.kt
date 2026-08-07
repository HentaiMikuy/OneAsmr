package com.oneasmr.app.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaButtonReceiver
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device probes for the Task 17 playback core (plan Task 17 QA — the
 * documented on-device equivalents of real-world events):
 *
 * 1. [focusLossPausesPlayback]: a competing audio app requests permanent
 *    audio focus (real [AudioManager.requestAudioFocus] through the system)
 *    → ExoPlayer's handleAudioFocus must pause; after abandon, playback
 *    resumes on demand.
 * 2. [mediaButtonTogglesPlayback]: a REAL Bluetooth-style media-button
 *    KeyEvent routed through the manifest [MediaButtonReceiver] (the API
 *    26-32 headset path; API 33+ media buttons go through the platform
 *    session) toggles play/pause.
 * 3. [playbackResumptionRestoresQueue]: with an EMPTY playlist, a play
 *    request hits [MediaSession.Callback.onPlaybackResumption] (Android 13+
 *    headset reconnect path) — the session must restore the last queue and
 *    start playing.
 *
 * Headphone-unplug (ACTION_AUDIO_BECOMING_NOISY) is NOT probed here: the
 * broadcast is protected — neither shell nor app can send it (verified
 * SecurityException on API 35) — so that path is verified with the same
 * production wiring on Robolectric in AudioBecomingNoisyTest (media3's own
 * methodology for this feature).
 *
 * Playback uses a WAV seeded into the app's private files dir (file:// uri —
 * no SAF grant needed, fully self-contained), so the probes run against the
 * REAL [PlaybackService] with the app's Hilt-injected singleton player.
 *
 * Threading: EVERY [MediaController] call (mutators AND getters) must run on
 * its application (main) thread in media3 1.10.1 — state is observed through
 * a [Player.Listener] recorder registered on the main thread, never by
 * polling controller getters from the test thread.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackProbeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val tag = "PlaybackProbe"

    private lateinit var audioManager: AudioManager
    private var controller: MediaController? = null
    private val recorder = StateRecorder()
    private var audioFocusRequest: AudioFocusRequest? = null
    private val focusGained = CountDownLatch(1)

    /** Main-thread listener that records the player state for the test thread. */
    private class StateRecorder : Player.Listener {
        @Volatile var isPlaying = false
            private set
        @Volatile var mediaItemCount = 0
            private set
        @Volatile var currentMediaId: String? = null
            private set

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            this.isPlaying = isPlaying
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            currentMediaId = mediaItem?.mediaId
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            mediaItemCount = timeline.windowCount
        }
    }

    @Before
    fun connect() {
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        controller = awaitController(future)
        assertNotNull(controller)
        runOnController { it.addListener(recorder) }
        Log.i(tag, "controller connected to ${PlaybackService::class.java.simpleName}")
    }

    @After
    fun cleanup() {
        abandonFocus()
        runOnController { it.playWhenReady = false }
        runOnController { it.release() }
        controller = null
    }

    @Test
    fun focusLossPausesPlayback() {
        startProbePlayback()
        await("playback should start", { recorder.isPlaying })

        // Competing audio app requests PERMANENT focus (the "another audio
        // app requesting focus" acceptance; gsm call not available on the
        // emulator — this is the documented equivalent).
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            Log.i(tag, "audio focus change: $change")
            if (change == AudioManager.AUDIOFOCUS_GAIN) focusGained.countDown()
        }
        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setOnAudioFocusChangeListener(listener)
            .build()
        val result = audioManager.requestAudioFocus(audioFocusRequest!!)
        assertEquals("focus request must succeed", AudioManager.AUDIOFOCUS_REQUEST_GRANTED, result)
        await("player must pause on focus loss", { !recorder.isPlaying })

        // Focus released: the app can resume on demand.
        abandonFocus()
        runOnController { it.play() }
        await("playback resumes after focus release", { recorder.isPlaying })
        Log.i(tag, "focus-loss pause verified")
    }

    @Test
    fun mediaButtonTogglesPlayback() {
        startProbePlayback()
        await("playback should start", { recorder.isPlaying })

        // Bluetooth-style headset button press (KEYCODE_MEDIA_PLAY_PAUSE)
        // through the manifest MediaButtonReceiver.
        sendMediaButton(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        await("media button must pause", { !recorder.isPlaying })

        sendMediaButton(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        await("media button must resume", { recorder.isPlaying })
        Log.i(tag, "media-button toggle verified")
    }

    @Test
    fun playbackResumptionRestoresQueue() {
        startProbePlayback()
        await("playback should start", { recorder.isPlaying })
        await("current item must be known", { recorder.currentMediaId != null })
        val mediaIdBefore = recorder.currentMediaId

        // Simulate "process resumed with empty playlist": clear the queue,
        // then a headset PLAY press arrives -> onPlaybackResumption must
        // restore the last queue (Android 13+ headset reconnect behavior).
        runOnController { it.clearMediaItems() }
        await("playlist must be empty", { recorder.mediaItemCount == 0 })

        sendMediaButton(KeyEvent.KEYCODE_MEDIA_PLAY)
        await(
            "resumption must restore the queue and start playing",
            { recorder.mediaItemCount > 0 && recorder.isPlaying },
            timeoutMs = 15_000,
        )
        assertEquals(
            "resumed mediaId must match the stored trackKey mediaId",
            mediaIdBefore,
            recorder.currentMediaId,
        )
        Log.i(tag, "playback resumption verified: restored ${recorder.mediaItemCount} item(s)")
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun startProbePlayback() {
        runOnController {
            it.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(seedToneFile())))
            it.prepare()
            it.play()
        }
    }

    private fun seedToneFile(): File {
        val file = File(context.filesDir, "probe_tone.wav")
        file.delete() // always regenerate — a stale/corrupt header must never be reused
        // 8 kHz mono 16-bit sine at 440 Hz, 8 seconds — pure deterministic bytes.
        val sampleRate = 8000
        val seconds = 8
        val sampleCount = sampleRate * seconds
        val data = ByteArray(44 + sampleCount * 2)
        fun writeInt(data: ByteArray, offset: Int, value: Int) {
            data[offset] = (value and 0xFF).toByte()
            data[offset + 1] = ((value shr 8) and 0xFF).toByte()
            data[offset + 2] = ((value shr 16) and 0xFF).toByte()
            data[offset + 3] = ((value shr 24) and 0xFF).toByte()
        }
        fun writeShort(data: ByteArray, offset: Int, value: Int) {
            data[offset] = (value and 0xFF).toByte()
            data[offset + 1] = ((value shr 8) and 0xFF).toByte()
        }
        writeInt(data, 0, 0x46464952) // "RIFF"
        writeInt(data, 4, data.size - 8)
        writeInt(data, 8, 0x45564157) // "WAVE"
        writeInt(data, 12, 0x20746D66) // "fmt "
        writeInt(data, 16, 16)
        writeShort(data, 20, 1) // PCM
        writeShort(data, 22, 1) // mono
        writeInt(data, 24, sampleRate)
        writeInt(data, 28, sampleRate * 2)
        writeShort(data, 32, 2)
        writeShort(data, 34, 16)
        writeInt(data, 36, 0x61746164) // "data"
        writeInt(data, 40, sampleCount * 2)
        for (i in 0 until sampleCount) {
            val sample = (Math.sin(2.0 * Math.PI * 440.0 * i / sampleRate) * 0.4 * Short.MAX_VALUE).toInt()
            writeShort(data, 44 + i * 2, sample)
        }
        file.writeBytes(data)
        Log.i(tag, "seeded tone file: ${file.absolutePath} (${file.length()} bytes)")
        return file
    }

    private fun sendMediaButton(keyCode: Int) {
        val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
            .setPackage(context.packageName)
            .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        context.sendBroadcast(intent)
        Log.i(tag, "sent media-button keyCode $keyCode")
    }

    private fun abandonFocus() {
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        audioFocusRequest = null
    }

    private fun awaitController(
        future: com.google.common.util.concurrent.ListenableFuture<MediaController>,
    ): MediaController? {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline && !future.isDone) {
            SystemClock.sleep(100)
        }
        return if (future.isDone) runCatching { future.get() }.getOrNull() else null
    }

    /** MediaController calls (mutators AND getters) run on its application thread. */
    private fun runOnController(action: (MediaController) -> Unit) {
        val c = checkNotNull(controller)
        val done = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            action(c)
            done.countDown()
        }
        try {
            assertTrue("controller action timed out", done.await(5, TimeUnit.SECONDS))
        } catch (e: InterruptedException) {
            fail("interrupted waiting for controller action: $e")
        }
    }

    private fun await(
        message: String,
        predicate: () -> Boolean,
        timeoutMs: Long = 10_000,
    ) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return
            SystemClock.sleep(100)
        }
        fail(
            "TIMEOUT: $message (playing=${recorder.isPlaying}, items=${recorder.mediaItemCount}, " +
                "mediaId=${recorder.currentMediaId})",
        )
    }
}
