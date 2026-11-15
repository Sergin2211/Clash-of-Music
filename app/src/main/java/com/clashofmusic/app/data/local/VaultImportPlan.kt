package com.clashofmusic.app.data.local

import com.clashofmusic.app.data.database.entity.*
import com.clashofmusic.app.domain.model.DownloadedSong
import java.io.File

data class VaultImportResult(val songsAdded: Int, val playlistsAdded: Int, val linksAdded: Int, val unavailableSongs: Int)

internal data class VaultImportPlan(
    val songs: List<DownloadedSongEntity>,
    val playlists: List<PlaylistEntity>,
    val links: List<PlaylistSongEntity>,
    val unavailable: Int,
    val identities: Map<String, String> = emptyMap()
) {
    val result get() = VaultImportResult(songs.size, playlists.size, links.size, unavailable)
}

/** Additive restore: existing metadata, collections, relations and their order remain authoritative. */
internal object VaultImportPlanner {
    fun plan(data: VaultData, current: List<DownloadedSongEntity>, available: List<DownloadedSong>,
             playlists: List<PlaylistEntity>, relations: List<PlaylistSongEntity>): VaultImportPlan {
        VaultTransfer.validate(data)
        val files = available.distinctBy { it.filePath }
        val byName = files.groupBy { File(it.filePath).name }
        val savedNameCounts = data.songs.groupingBy { it.fileName }.eachCount()
        val existingByPath = current.associateBy { it.filePath }
        val newSongs = mutableListOf<DownloadedSongEntity>()
        val identities = mutableMapOf<String, String>()
        val usedAliases = current.flatMap { listOf(it.id, it.songId) }.toMutableSet()
        val claimedPaths = mutableSetOf<String>()
        var unavailable = 0
        for (saved in data.songs) {
            val matches = current.filter { it.id == saved.id || it.songId == saved.songId }
            if (matches.size > 1) { unavailable++; continue }
            val existing = matches.singleOrNull()
            val file = if (existing != null) files.singleOrNull { it.filePath == existing.filePath }
                else byName[saved.fileName]?.singleOrNull()?.takeIf { savedNameCounts[saved.fileName] == 1 }
            if (file == null || !claimedPaths.add(file.filePath)) { unavailable++; continue }
            val local = existingByPath[file.filePath]
            val localId = if (local != null) local.id else {
                if (saved.id in usedAliases || saved.songId in usedAliases) { unavailable++; continue }
                newSongs.add(DownloadedSongEntity.fromDomain(file.copy(
                    id = saved.id, songId = saved.songId, title = saved.title, artist = saved.artist,
                    album = saved.album, genre = saved.genre, downloadedAt = saved.downloadedAt,
                    thumbnailUrl = file.thumbnailUrl ?: saved.thumbnailUrl?.takeIf {
                        it.startsWith("https://") || it.startsWith("http://")
                    }
                )).copy(id = saved.id))
                usedAliases.add(saved.id); usedAliases.add(saved.songId)
                saved.id
            }
            identities[saved.id] = localId
            identities[saved.songId] = localId
        }
        val existingPlaylistIds = playlists.map { it.id }.toSet()
        val newPlaylists = data.playlists.filter { it.id !in existingPlaylistIds }.map {
            PlaylistEntity(it.id, it.name, it.description, it.thumbnailUrl?.takeIf { url ->
                url.startsWith("https://") || url.startsWith("http://")
            }, it.isAlbum, it.createdAt)
        }
        val links = mutableListOf<PlaylistSongEntity>()
        for (saved in data.playlists) {
            val existing = relations.filter { it.playlistId == saved.id }
            val linked = existing.map { it.songId }.toMutableSet()
            var order = (existing.maxOfOrNull { it.sortOrder } ?: -1) + 1
            for (id in saved.songIds) {
                val localId = identities[id] ?: continue
                if (linked.add(localId)) links.add(PlaylistSongEntity(saved.id, localId, sortOrder = order++))
            }
        }
        return VaultImportPlan(newSongs, newPlaylists, links, unavailable, identities)
    }
}
