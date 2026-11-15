package com.clashofmusic.app.data.player

import com.clashofmusic.app.domain.model.DownloadedSong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class QueueEntryOrigin { LIST, MANUAL, PREVIEW, RADIO }

data class PlaybackQueueEntry(
    val id: String,
    val song: DownloadedSong,
    val origin: QueueEntryOrigin,
    val originalOrder: Long,
    val isQueued: Boolean = origin == QueueEntryOrigin.MANUAL
)

data class PlaybackQueueState(
    val entries: List<PlaybackQueueEntry> = emptyList(),
    val currentEntryId: String? = null,
    val shuffleEnabled: Boolean = false,
    val revision: Long = 0
) {
    val currentIndex: Int get() = entries.indexOfFirst { it.id == currentEntryId }
    val current: PlaybackQueueEntry? get() = entries.getOrNull(currentIndex)
    val upcoming: List<PlaybackQueueEntry> get() = if (currentIndex >= 0) entries.drop(currentIndex + 1) else emptyList()
    val manualQueue: List<PlaybackQueueEntry> get() = upcoming.filter { it.isQueued }
    val followingList: List<PlaybackQueueEntry> get() = upcoming.filterNot { it.isQueued }
}

/** The physical order is the playback order, including shuffle and manual priority. */
internal class PlaybackQueue(
    private val shuffle: (List<PlaybackQueueEntry>) -> List<PlaybackQueueEntry> = { it.shuffled() }
) {
    private val mutableState = MutableStateFlow(PlaybackQueueState())
    val state = mutableState.asStateFlow()
    val value get() = mutableState.value
    private var sequence = 0L
    private val startedListEntries = mutableSetOf<String>()

    private fun entry(song: DownloadedSong, origin: QueueEntryOrigin): PlaybackQueueEntry {
        val order = sequence++
        return PlaybackQueueEntry("clash-entry-$order", song, origin, order)
    }

    private fun publish(entries: List<PlaybackQueueEntry>, currentId: String? = value.currentEntryId, shuffled: Boolean = value.shuffleEnabled) {
        require(entries.map { it.id }.distinct().size == entries.size)
        require(currentId == null || entries.any { it.id == currentId })
        mutableState.value = PlaybackQueueState(entries, currentId, shuffled, value.revision + 1)
    }

    fun load(songs: List<DownloadedSong>, targetIndex: Int) {
        if (songs.isEmpty()) { clear(); return }
        val entries = songs.map { entry(it, QueueEntryOrigin.LIST) }
        val index = targetIndex.coerceIn(entries.indices)
        startedListEntries.clear()
        startedListEntries.add(entries[index].id)
        publish(entries, entries[index].id)
        if (value.shuffleEnabled) setShuffle(true)
    }

    fun select(entryId: String): Boolean {
        val selected = value.entries.firstOrNull { it.id == entryId } ?: return false
        if (selected.origin == QueueEntryOrigin.LIST) startedListEntries.add(entryId)
        if (value.currentEntryId != entryId || selected.isQueued) {
            val entries = value.entries.map { if (it.id == entryId) it.copy(isQueued = false) else it }
            val index = entries.indexOfFirst { it.id == entryId }
            val upcoming = entries.drop(index + 1)
            // Returning to history must keep pending manual entries next, as the UI promises.
            publish(entries.take(index + 1) + upcoming.filter { it.isQueued } + upcoming.filterNot { it.isQueued }, entryId)
        }
        return true
    }

    fun enqueue(song: DownloadedSong, playNext: Boolean) {
        if (value.current == null) { load(listOf(song), 0); return }
        val index = value.currentIndex + 1 + if (playNext) 0 else value.upcoming.takeWhile { it.isQueued }.size
        val entries = value.entries.toMutableList()
        entries.add(index, entry(song, QueueEntryOrigin.MANUAL))
        publish(entries)
    }

    fun preview(song: DownloadedSong): String {
        val preview = entry(song, QueueEntryOrigin.PREVIEW)
        val entries = value.entries.toMutableList()
        entries.add((value.currentIndex + 1).coerceAtLeast(0), preview)
        publish(entries, preview.id)
        return preview.id
    }

    fun removeQueuedEntry(entryId: String) {
        if (value.manualQueue.none { it.id == entryId }) return
        publish(value.entries.filterNot { it.id == entryId })
    }

    fun clearManualQueue() {
        val pendingIds = value.manualQueue.map { it.id }.toSet()
        if (pendingIds.isNotEmpty()) publish(value.entries.filterNot { it.id in pendingIds })
    }

    fun setShuffle(enabled: Boolean) {
        if (value.current == null) { publish(value.entries, shuffled = enabled); return }
        val prefix = value.entries.take(value.currentIndex + 1)
        // A jump moves the current index past tracks that haven't been selected for playback.
        val (keptPrefix, skipped) = prefix.partition {
            !it.isQueued && (it.origin != QueueEntryOrigin.LIST || it.id in startedListEntries)
        }
        val (manual, rest) = (skipped + value.upcoming).partition { it.isQueued }
        val ordered = if (enabled) shuffle(rest) else rest.sortedBy { it.originalOrder }
        publish(keptPrefix + manual + ordered, shuffled = enabled)
    }

    fun updateSongMetadata(song: DownloadedSong) {
        val entries = value.entries.map { entry ->
            if (entry.origin != QueueEntryOrigin.PREVIEW && entry.song.songId == song.songId && entry.song.filePath == song.filePath)
                entry.copy(song = song) else entry
        }
        if (entries != value.entries) publish(entries)
    }

    fun append(songs: List<DownloadedSong>) {
        if (songs.isEmpty()) return
        if (value.current == null) { load(songs, 0); return }
        publish(value.entries + songs.map { entry(it, QueueEntryOrigin.LIST) })
    }

    fun loadRadio(song: DownloadedSong) {
        startedListEntries.clear()
        val first = entry(song, QueueEntryOrigin.RADIO)
        publish(listOf(first), first.id, false)
    }

    fun appendRadio(songs: List<DownloadedSong>) {
        if (songs.isNotEmpty()) publish(value.entries + songs.map { entry(it, QueueEntryOrigin.RADIO) })
    }

    fun trimRadioHistory() {
        val start = (value.currentIndex - RadioWindow.BEHIND).coerceAtLeast(0)
        if (start > 0) publish(value.entries.drop(start))
    }

    fun clear() {
        startedListEntries.clear()
        publish(emptyList(), null)
    }
}
