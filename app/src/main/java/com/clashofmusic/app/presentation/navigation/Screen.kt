package com.clashofmusic.app.presentation.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, val title: String, val icon: ImageVector? = null) {
    object Home : Screen("home", "Inicio", Icons.Rounded.Home)
    object Search : Screen("search", "Buscar", Icons.Rounded.Search) {
        const val pattern = "search?q={query}"
        fun createRoute(query: String? = null): String = if (query == null) route else
            "$route?q=" + java.net.URLEncoder.encode(query, "UTF-8").replace("+", "%20")
    }
    object Downloads : Screen("downloads", "Descargas", Icons.Rounded.Download)
    object Library : Screen("library", "Biblioteca", Icons.Rounded.LibraryMusic)
    object Settings : Screen("settings", "Ajustes", Icons.Rounded.Settings)

    object SongDetails : Screen("song_details/{songId}", "Detalles") {
        fun createRoute(songId: String): String = "song_details/$songId"
    }

    object PlaylistDetails : Screen("playlist_details/{playlistId}", "Playlist") {
        fun createRoute(playlistId: String): String = "playlist_details/$playlistId"
    }

    companion object {
        val bottomNavScreens = listOf(Home, Search, Downloads, Library, Settings)
    }
}
