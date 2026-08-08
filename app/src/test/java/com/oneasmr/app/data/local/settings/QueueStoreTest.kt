package com.oneasmr.app.data.local.settings

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.oneasmr.app.data.local.TestDataStoreFile
import com.oneasmr.app.domain.player.PlayQueueItem
import com.oneasmr.app.domain.player.RepeatMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * QueueStore persistence (plan Task 18): JSON queue + typed mode keys,
 * cross-instance persistence ("restart"), corrupt-blob fallback.
 */
class QueueStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(name: String) = TestDataStoreFile(tmp.newFile("$name.preferences_pb"))

    private fun item(i: Int) = PlayQueueItem(
        sourceScope = "local",
        rjCode = "RJ100200",
        trackIndex = i,
        trackTitle = "track$i.mp3",
        workTitle = "w",
        uri = "content://doc/$i",
    )

    @Test
    fun `defaults are empty queue off repeat no shuffle speed 1x`() = runTest {
        val store = QueueStore(file("defaults").open())
        assertNull(store.queue.first())
        assertEquals(RepeatMode.OFF, store.repeatMode.first())
        assertFalse(store.shuffleEnabled.first())
        assertEquals(1f, store.speed.first())
    }

    @Test
    fun `full session round trip persists across store instances`() = runTest {
        val tf = file("session")
        val store = QueueStore(tf.open())
        store.saveQueue(com.oneasmr.app.data.local.settings.PersistedQueue(
            workId = "local:RJ100200",
            items = listOf(item(1), item(2), item(3)),
            currentIndex = 1,
        ))
        store.setRepeatMode(RepeatMode.ONE)
        store.setShuffleEnabled(true)
        store.setSpeed(1.7f)

        val restarted = QueueStore(tf.restart())
        val restored = restarted.queue.first()!!
        assertEquals("local:RJ100200", restored.workId)
        assertEquals(listOf(1, 2, 3), restored.items.map { it.trackIndex })
        assertEquals("content://doc/2", restored.items[1].uri)
        assertEquals(1, restored.currentIndex)
        assertTrue(restored.isRestorable)
        assertEquals(RepeatMode.ONE, restarted.repeatMode.first())
        assertTrue(restarted.shuffleEnabled.first())
        assertEquals(1.7f, restarted.speed.first())
    }

    @Test
    fun `saving null clears the persisted queue but keeps modes`() = runTest {
        val tf = file("clear")
        val store = QueueStore(tf.open())
        store.saveQueue(com.oneasmr.app.data.local.settings.PersistedQueue(
            workId = "local:RJ100200", items = listOf(item(1)), currentIndex = 0,
        ))
        store.setRepeatMode(RepeatMode.ALL)
        store.saveQueue(null)

        val restarted = QueueStore(tf.restart())
        assertNull(restarted.queue.first())
        assertEquals(RepeatMode.ALL, restarted.repeatMode.first())
    }

    @Test
    fun `corrupt queue json falls back to empty`() = runTest {
        val tf = file("corrupt")
        val dataStore = tf.open()
        dataStore.edit { it[stringPreferencesKey("queue_json")] = "{not json" }
        val store = QueueStore(dataStore)
        assertNull(store.queue.first())
    }

    @Test
    fun `speed is normalized on write`() = runTest {
        val tf = file("speed")
        val store = QueueStore(tf.open())
        store.setSpeed(1.06f)
        assertEquals(1.1f, QueueStore(tf.restart()).speed.first())
    }

    @Test
    fun `stale json with unknown fields stays readable`() = runTest {
        val tf = file("stale")
        val dataStore = tf.open()
        // Simulate a future build's blob carrying an extra field.
        dataStore.edit {
            it[stringPreferencesKey("queue_json")] =
                """{"workId":"local:RJ1","items":[{"sourceScope":"local","rjCode":"RJ1","trackIndex":1,"trackTitle":"a.mp3","workTitle":"w","uri":"content://a"}],"currentIndex":0,"futureField":"x"}"""
        }
        val restored = QueueStore(dataStore).queue.first()!!
        assertEquals(1, restored.items.size)
        assertEquals("local:RJ1", restored.workId)
    }
}
