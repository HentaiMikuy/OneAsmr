package com.oneasmr.app.player

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Headphone-unplug behavior — plan Task 17 "拔耳机暂停" (the ACTION_AUDIO_BECOMING_NOISY
 * path). The broadcast is PROTECTED: only the system can send it, and neither
 * shell nor the app itself may broadcast it on-device (API 35 — verified:
 * SecurityException from both `am broadcast` and `context.sendBroadcast`),
 * and the emulator has no headset jack to unplug.
 *
 * Documented equivalent (and media3's own methodology for this exact
 * feature): Robolectric delivers protected broadcasts to dynamically
 * registered receivers. This test exercises the PRODUCTION wiring —
 * [PlayerModule.provideExoPlayer] with `setHandleAudioBecomingNoisy(true)` —
 * plays a generated WAV, then asserts the player pauses when the becoming
 * noisy broadcast arrives.
 *
 * On-device QA covers the rest of the audio-focus surface (focus loss,
 * media buttons, resumption) via PlaybackProbeTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioBecomingNoisyTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun becomingNoisyBroadcastPausesThePlayer() {
        val player = PlayerModule.provideExoPlayer(context)
        try {
            player.setMediaItem(MediaItem.fromUri(seedToneFile(context)))
            player.prepare()
            player.play()
            // Give the player's real handler threads a moment to start.
            assertTrue(waitUntil({ player.isPlaying || player.playbackState == androidx.media3.common.Player.STATE_READY }))
            assertTrue("player must be playing before the unplug", player.playWhenReady)

            // System-level broadcast (Robolectric delivers protected broadcasts).
            context.sendBroadcast(Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            shadowOf(Looper.getMainLooper()).idle()

            assertFalse(
                "player must pause when audio becomes noisy (headphone unplug)",
                player.playWhenReady,
            )
        } finally {
            player.release()
        }
    }

    private fun waitUntil(predicate: () -> Boolean, timeoutMs: Long = 8_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
        }
        return false
    }

    private fun seedToneFile(context: Context): Uri {
        val file = File(context.cacheDir, "noisy_tone.wav")
        if (!file.exists()) {
            // 8 kHz mono 16-bit sine, 2 seconds — same generator as the device probe.
            val sampleRate = 8000
            val sampleCount = sampleRate * 2
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
        }
        return Uri.fromFile(file)
    }
}
