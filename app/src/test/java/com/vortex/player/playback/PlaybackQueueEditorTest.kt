package com.vortex.player.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackQueueEditorTest {

    @Test
    fun `identity validator accepts the same current item at a new index`() {
        assertTrue(
            PlaybackQueueEditor.preservesCurrent(
                entries = listOf("a", "b", "c"),
                currentIndex = 1,
                updatedEntries = listOf("c", "a", "b"),
                updatedCurrentIndex = 2,
                sameIdentity = String::equals
            )
        )
    }

    @Test
    fun `identity validator rejects a replacement at the same index`() {
        assertFalse(
            PlaybackQueueEditor.preservesCurrent(
                entries = listOf("a", "b", "c"),
                currentIndex = 1,
                updatedEntries = listOf("a", "x", "c"),
                updatedCurrentIndex = 1,
                sameIdentity = String::equals
            )
        )
    }

    @Test
    fun `identity validator rejects empty or invalid queues`() {
        assertFalse(
            PlaybackQueueEditor.preservesCurrent(
                entries = emptyList<String>(),
                currentIndex = 0,
                updatedEntries = listOf("a"),
                updatedCurrentIndex = 0,
                sameIdentity = String::equals
            )
        )
        assertFalse(
            PlaybackQueueEditor.preservesCurrent(
                entries = listOf("a"),
                currentIndex = 0,
                updatedEntries = emptyList(),
                updatedCurrentIndex = 0,
                sameIdentity = String::equals
            )
        )
    }

    @Test
    fun `moving another item across current keeps the same media selected`() {
        val result = PlaybackQueueEditor.move(
            entries = listOf("a", "b", "c", "d"),
            currentIndex = 2,
            fromIndex = 0,
            toIndex = 3
        )

        assertEquals(listOf("b", "c", "d", "a"), result.entries)
        assertEquals(1, result.currentIndex)
        assertEquals("c", result.entries[result.currentIndex])
    }

    @Test
    fun `moving current updates its index without changing identity`() {
        val result = PlaybackQueueEditor.move(listOf("a", "b", "c"), 1, 1, 2)

        assertEquals(listOf("a", "c", "b"), result.entries)
        assertEquals(2, result.currentIndex)
        assertEquals("b", result.entries[result.currentIndex])
    }

    @Test
    fun `removing items before current shifts the index`() {
        val result = PlaybackQueueEditor.remove(
            entries = listOf("a", "b", "c", "d"),
            currentIndex = 3,
            rawIndices = setOf(0, 2)
        )

        assertEquals(listOf("b", "d"), result.entries)
        assertEquals(1, result.currentIndex)
        assertFalse(result.currentRemoved)
    }

    @Test
    fun `removing current chooses the next surviving item`() {
        val result = PlaybackQueueEditor.remove(
            entries = listOf("a", "b", "c", "d"),
            currentIndex = 1,
            rawIndices = setOf(1, 2)
        )

        assertEquals(listOf("a", "d"), result.entries)
        assertEquals(1, result.currentIndex)
        assertEquals("d", result.entries[result.currentIndex])
        assertTrue(result.currentRemoved)
    }

    @Test
    fun `removing the whole queue returns an empty safe state`() {
        val result = PlaybackQueueEditor.remove(listOf("a", "b"), 0, setOf(0, 1))

        assertTrue(result.entries.isEmpty())
        assertEquals(0, result.currentIndex)
        assertTrue(result.currentRemoved)
    }
}
