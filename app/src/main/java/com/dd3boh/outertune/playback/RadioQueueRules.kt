/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.models.MediaMetadata
import java.text.Normalizer
import java.util.Locale

/** Limits applied only to automatically recommended songs, never to the selected seed or hand-added songs. */
internal object RadioQueueRules {
    const val SONG_COOLDOWN_MS = 168L * 60L * 60L * 1000L
    const val ARTIST_WINDOW_MS = 2L * 60L * 60L * 1000L
    const val MAX_PER_ARTIST = 2
    const val UNKNOWN_DURATION_MS = 3L * 60L * 1000L

    data class Play(
        val songId: String,
        val artistKeys: Set<String>,
        val atMs: Long,
    )

    data class Scheduled(
        val songId: String,
        val artistKeys: Set<String>,
        val atMs: Long,
    )

    data class Initial(
        val items: List<MediaMetadata>,
        val startIndex: Int,
    )

    /** The selected song remains in place even if it was played recently; only radio suggestions are filtered. */
    fun filterInitial(
        items: List<MediaMetadata>,
        startIndex: Int,
        history: List<Play>,
        nowMs: Long,
        cooldownEnabled: Boolean = false,
        artistLimitEnabled: Boolean = false,
        blockedSongIds: Set<String> = emptySet(),
        blockedArtistKeys: Set<String> = emptySet(),
    ): Initial {
        if (items.isEmpty()) return Initial(items, 0)
        val seedIndex = startIndex.coerceIn(0, items.lastIndex)
        val seed = items[seedIndex]
        val seedAlreadyLogged = history.any {
            it.songId == seed.id && kotlin.math.abs(nowMs - it.atMs) <= 5L * 60L * 1000L
        }
        val seedPlay = if (seedAlreadyLogged) emptyList() else listOf(
            Play(seed.id, artistKeys(seed.artists), nowMs)
        )
        val filtered = filterAfter(
            items = items,
            preserveIndex = seedIndex,
            history = history + seedPlay,
            reserved = emptyList(),
            firstCandidateAtMs = nowMs + durationMs(seed.duration),
            nowMs = nowMs,
            cooldownEnabled = cooldownEnabled,
            artistLimitEnabled = artistLimitEnabled,
            blockedSongIds = blockedSongIds,
            blockedArtistKeys = blockedArtistKeys,
        )
        return Initial(filtered, seedIndex)
    }

    /** Filters a page, using the planned end of the already queued songs as its first start time. */
    fun filterPage(
        candidates: List<MediaMetadata>,
        history: List<Play>,
        reserved: List<Scheduled>,
        firstCandidateAtMs: Long,
        nowMs: Long,
        cooldownEnabled: Boolean = false,
        artistLimitEnabled: Boolean = false,
        blockedSongIds: Set<String> = emptySet(),
        blockedArtistKeys: Set<String> = emptySet(),
    ): List<MediaMetadata> = filterAfter(
        items = candidates,
        preserveIndex = null,
        history = history,
        reserved = reserved,
        firstCandidateAtMs = firstCandidateAtMs,
        nowMs = nowMs,
        cooldownEnabled = cooldownEnabled,
        artistLimitEnabled = artistLimitEnabled,
        blockedSongIds = blockedSongIds,
        blockedArtistKeys = blockedArtistKeys,
    )

    private fun filterAfter(
        items: List<MediaMetadata>,
        preserveIndex: Int?,
        history: List<Play>,
        reserved: List<Scheduled>,
        firstCandidateAtMs: Long,
        nowMs: Long,
        cooldownEnabled: Boolean,
        artistLimitEnabled: Boolean,
        blockedSongIds: Set<String>,
        blockedArtistKeys: Set<String>,
    ): List<MediaMetadata> {
        if (items.isEmpty()) return items
        // The query can supply the full seven-day history. Keep it when either rule is active:
        // the rolling artist window is evaluated against each planned start time, not just now.
        val recentHistory = if (cooldownEnabled || artistLimitEnabled) {
            history.filter { it.atMs > nowMs - SONG_COOLDOWN_MS && it.atMs <= nowMs }
        } else emptyList()
        val playedIds = if (cooldownEnabled) recentHistory.mapTo(HashSet()) { it.songId } else emptySet()
        val reservedIds = reserved.mapTo(HashSet()) { it.songId }
        val acceptedIds = HashSet<String>()
        val planned = ArrayList<Play>(if (artistLimitEnabled) recentHistory.size + reserved.size + items.size else 0)
        if (artistLimitEnabled) {
            planned.addAll(recentHistory)
            planned.addAll(reserved.map { Play(it.songId, it.artistKeys, it.atMs) })
        }

        val output = items.map { it as MediaMetadata? }.toMutableList()
        var nextAt = firstCandidateAtMs
        val first = (preserveIndex?.plus(1) ?: 0).coerceAtLeast(0)
        for (index in first until items.size) {
            if (index == preserveIndex) continue
            val candidate = items[index]
            val keys = artistKeys(candidate.artists)
            val recentlyPlayed = candidate.id in playedIds
            val blocked = candidate.id in blockedSongIds || keys.any { it in blockedArtistKeys }
            val alreadyReserved = candidate.id in reservedIds
            val repeatedInPage = candidate.id in acceptedIds
            val artistLimitReached = artistLimitEnabled && keys.any { key ->
                planned.count { key in it.artistKeys && it.atMs > nextAt - ARTIST_WINDOW_MS && it.atMs <= nextAt } >= MAX_PER_ARTIST
            }
            if (blocked || recentlyPlayed || alreadyReserved || repeatedInPage || artistLimitReached) {
                output[index] = null
            } else {
                acceptedIds += candidate.id
                if (artistLimitEnabled) planned += Play(candidate.id, keys, nextAt)
                nextAt += durationMs(candidate.duration)
            }
        }
        return output.filterNotNull()
    }

    fun artistKeys(artists: List<MediaMetadata.Artist>): Set<String> = buildSet {
        artists.forEach { artist ->
            artist.id?.trim()?.takeIf(String::isNotEmpty)?.let { add("id:$it") }
            normalizeName(artist.name).takeIf(String::isNotEmpty)?.let { add("name:$it") }
        }
    }

    fun artistKeys(artistId: String?, artistName: String?): Set<String> = buildSet {
        artistId?.trim()?.takeIf(String::isNotEmpty)?.let { add("id:$it") }
        artistName?.let(::normalizeName)?.takeIf(String::isNotEmpty)?.let { add("name:$it") }
    }

    private fun normalizeName(name: String): String = Normalizer
        .normalize(name.trim(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun durationMs(seconds: Int): Long =
        if (seconds > 0) seconds.toLong() * 1000L else UNKNOWN_DURATION_MS
}
