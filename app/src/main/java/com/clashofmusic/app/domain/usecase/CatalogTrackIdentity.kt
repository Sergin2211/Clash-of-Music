package com.clashofmusic.app.domain.usecase

import com.clashofmusic.app.domain.model.*
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/** Catalog labels identify the recording; a newly ranked video must not create a second copy. */
data class CatalogTrackIdentity(val title: String, val artist: String, val album: String?, val duration: Long) {
    private fun normalized(value: String?) = Normalizer.normalize(value.orEmpty(), Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).trim().replace(spaces, " ")

    private val titleKey = normalized(title)
    private val artistKey = normalized(artist)
    private val albumKey = normalized(album)
    val key = listOf(artistKey, titleKey).joinToString("\u0000")
    companion object { private val spaces = Regex("\\s+") }
    @Suppress("UNUSED_PARAMETER")
    fun matches(title: String, artist: String, album: String?): Boolean =
        titleKey == normalized(title) && artistKey == normalized(artist)

    fun find(songs: List<DownloadedSong>): DownloadedSong? = songs.asSequence()
        .filter { matches(it.title, it.artist, it.album) }
        // If a historical duplicate exists, prefer the recording with the expected duration.
        .sortedWith(compareBy<DownloadedSong> {
            if (duration > 0 && it.durationSeconds > 0) abs(duration - it.durationSeconds) else Long.MAX_VALUE
        }.thenBy { if (albumKey == normalized(it.album)) 0 else 1 }.thenBy { it.downloadedAt }).firstOrNull()
}

data class CollectionDownloadState(
    val checking: Boolean = true,
    val complete: Boolean = false,
    val active: Boolean = false,
    val missing: Int = 0
) {
    val canDownload get() = !checking && !complete && !active
}
