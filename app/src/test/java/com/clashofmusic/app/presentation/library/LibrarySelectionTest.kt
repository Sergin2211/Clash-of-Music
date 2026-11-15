package com.clashofmusic.app.presentation.library

import androidx.lifecycle.viewModelScope
import com.clashofmusic.app.domain.model.*
import com.clashofmusic.app.domain.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
class LibrarySelectionTest {
    private inline fun <reified T> fake(crossinline call: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ -> call(method.name) } as T

    @Test fun deselectingAllKeepsAlbumAndSelectionModeOpen() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val shared = DownloadedSong("a", "a", "Shared", "Artist", "Second", 100, null, "/a.mp3", "file:///a.mp3", "MP3", fileSizeBytes = 10, downloadedAt = 1)
        val albums = listOf(
            AlbumCollection(Playlist("first", "First", "Artist • Pop • 2022", isAlbum = true), listOf("a")),
            AlbumCollection(Playlist("second", "Second", "Artist • Pop • 2023", isAlbum = true), listOf("a")))
        val vm = LibraryViewModel(
            libraryRepository = fake { name -> when (name) {
                "observeDownloadedSongs" -> flowOf(listOf(shared))
                else -> error(name)
            } }, playlistRepository = fake { name -> when (name) {
                "observePlaylists" -> flowOf(albums.map { it.album })
                "observeAlbumCollections" -> flowOf(albums)
                else -> error(name)
            } }, favoritesRepository = fake { flowOf(emptyList<FavoriteSong>()) }, dataDispatcher = dispatcher, synchronizeOnStart = false)
        try {
            runCurrent()
            vm.onTabSelected(4)
            vm.selectAlbum(vm.uiState.value.albums.first())
            vm.setSelectionMode(true)
            vm.selectAll(vm.uiState.value.selectedAlbum!!.songs)
            assertEquals(setOf("a"), vm.uiState.value.selectedSongIds)
            vm.clearSelection()
            assertTrue(vm.uiState.value.isSelectionMode)
            assertNotNull(vm.uiState.value.selectedAlbum)
            assertTrue(vm.uiState.value.selectedSongIds.isEmpty())
            vm.selectAll(vm.uiState.value.selectedAlbum!!.songs)
            assertEquals(setOf("a"), vm.uiState.value.selectedSongIds)
            vm.setSelectionMode(false)
            assertFalse(vm.uiState.value.isSelectionMode)
        } finally { vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
}
