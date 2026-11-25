/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.models.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioQueueRulesTest {
    private val now = 1_800_000_000_000L

    private fun song(id: String, artist: String = "Artist", artistId: String? = "artist-id", duration: Int = 180) =
        MediaMetadata(
            id = id,
            title = "Song $id",
            artists = listOf(MediaMetadata.Artist(id = artistId, name = artist)),
            duration = duration,
            genre = null,
        )

    private fun play(id: String, at: Long, artist: String = "Artist", artistId: String? = "artist-id") =
        RadioQueueRules.Play(id, RadioQueueRules.artistKeys(artistId, artist), at)

    @Test
    fun `the initial radio keeps its selected seed but leaves a song played inside seven days out`() {
        val seed = song("seed")
        val recent = song("recent")
        val fresh = song("fresh", artist = "Another")
        val result = RadioQueueRules.filterInitial(
            items = listOf(seed, recent, fresh),
            startIndex = 0,
            history = listOf(play("seed", now - 60_000L), play("recent", now - 6L * 24L * 60L * 60L * 1000L)),
            nowMs = now,
        )

        assertEquals(0, result.startIndex)
        assertEquals(listOf("seed", "fresh"), result.items.map { it.id })
    }

    @Test
    fun `a song becomes eligible exactly at the seven day boundary`() {
        val boundary = song("boundary", artist = "New")
        val stillCooling = song("cooling", artist = "Other")
        val result = RadioQueueRules.filterPage(
            candidates = listOf(boundary, stillCooling),
            history = listOf(
                play("boundary", now - RadioQueueRules.SONG_COOLDOWN_MS),
                play("cooling", now - RadioQueueRules.SONG_COOLDOWN_MS + 1L),
            ),
            reserved = emptyList(),
            firstCandidateAtMs = now,
            nowMs = now,
        )

        assertEquals(listOf("boundary"), result.map { it.id })
    }

    @Test
    fun `the third song by an artist inside the moving two hour window is rejected`() {
        val result = RadioQueueRules.filterPage(
            candidates = listOf(song("third"), song("other", artist = "Different", artistId = "different-id")),
            history = listOf(play("first", now - 90L * 60L * 1000L), play("second", now - 30L * 60L * 1000L)),
            reserved = emptyList(),
            firstCandidateAtMs = now,
            nowMs = now,
        )

        assertEquals(listOf("other"), result.map { it.id })
    }

    @Test
    fun `the artist may return when the older plays have left the two hour window`() {
        val result = RadioQueueRules.filterPage(
            candidates = listOf(song("eligible")),
            history = listOf(play("first", now - 3L * 60L * 60L * 1000L), play("second", now - 2L * 60L * 60L * 1000L)),
            reserved = emptyList(),
            firstCandidateAtMs = now,
            nowMs = now,
        )

        assertEquals(listOf("eligible"), result.map { it.id })
    }

    @Test
    fun `the artist limit counts songs already scheduled in the current radio`() {
        val reserved = listOf(
            RadioQueueRules.Scheduled("queued-one", RadioQueueRules.artistKeys(listOf(song("a").artists[0])), now + 60_000L),
            RadioQueueRules.Scheduled("queued-two", RadioQueueRules.artistKeys(listOf(song("b").artists[0])), now + 120_000L),
        )
        val result = RadioQueueRules.filterPage(
            candidates = listOf(song("third")),
            history = emptyList(),
            reserved = reserved,
            firstCandidateAtMs = now + 180_000L,
            nowMs = now,
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `a selected seed counts toward its artist quota but is never removed`() {
        val seed = song("seed")
        val radioSong = song("radio-song")
        val result = RadioQueueRules.filterInitial(
            items = listOf(seed, radioSong),
            startIndex = 0,
            history = listOf(play("earlier", now - 30L * 60L * 1000L)),
            nowMs = now,
        )

        assertEquals(listOf("seed"), result.items.map { it.id })
        assertEquals("seed", result.items[result.startIndex].id)
    }
}
