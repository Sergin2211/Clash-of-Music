package com.clashofmusic.app.presentation.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.clashofmusic.app.presentation.components.MiniPlayerBottomBar
import com.clashofmusic.app.presentation.downloads.DownloadsScreen
import com.clashofmusic.app.presentation.home.HomeScreen
import com.clashofmusic.app.presentation.library.LibraryScreen
import com.clashofmusic.app.presentation.playlist.PlaylistDetailsScreen
import com.clashofmusic.app.presentation.search.SearchScreen
import com.clashofmusic.app.presentation.settings.SettingsScreen
import com.clashofmusic.app.presentation.songdetails.SongDetailsScreen
import com.clashofmusic.app.presentation.theme.AccentBerry
import com.clashofmusic.app.presentation.theme.DarkBackground
import com.clashofmusic.app.presentation.theme.DarkSurface

@Composable
fun AppNavigation(
    navController: NavHostController = rememberNavController(),
    openDownloads: Boolean = false,
    onDownloadsOpened: () -> Unit = {},
    downloadActivity: com.clashofmusic.app.services.DownloadActivityMonitor = com.clashofmusic.app.di.ServiceLocator.downloadActivity
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route.topLevelRoute()
    DisposableEffect(currentRoute, downloadActivity) {
        downloadActivity.viewingDownloads(currentRoute == Screen.Downloads.route)
        onDispose { downloadActivity.viewingDownloads(false) }
    }
    LaunchedEffect(openDownloads) {
        if (openDownloads) {
            navController.navigateToTopLevel(Screen.Downloads)
            onDownloadsOpened()
        }
    }

    val isTopLevelDestination = Screen.bottomNavScreens.any { it.route == currentRoute }

    Scaffold(
        containerColor = DarkBackground,
        bottomBar = {
            Column(modifier = Modifier.background(DarkBackground)
                .windowInsetsPadding(WindowInsets.navigationBars.only(
                    if (isTopLevelDestination) WindowInsetsSides.Horizontal
                    else WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))) {
                // Persistent MiniPlayer floating above bottom navigation
                MiniPlayerBottomBar()

                if (isTopLevelDestination) {
                    NavigationBar(
                        tonalElevation = 0.dp,
                        containerColor = DarkBackground
                    ) {
                        Screen.bottomNavScreens.forEach { screen ->
                            val selected = currentRoute == screen.route
                            if (screen == Screen.Downloads) {
                                DownloadNavigationItem(selected, onClick = {
                                    if (!selected) navController.navigateToTopLevel(screen)
                                }, monitor = downloadActivity)
                                return@forEach
                            }
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    if (currentRoute != screen.route) {
                                        navController.navigateToTopLevel(screen)
                                    }
                                },
                                icon = {
                                    screen.icon?.let { icon ->
                                        Icon(imageVector = icon, contentDescription = screen.title)
                                    }
                                },
                                label = {
                                    Text(
                                        text = screen.title,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (selected) AccentBerry else com.clashofmusic.app.presentation.theme.TextPrimaryDark
                                    )
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = AccentBerry,
                                    selectedTextColor = AccentBerry,
                                    indicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    onNavigateToSearch = { query ->
                        navController.navigateToTopLevel(Screen.Search, query)
                    },
                    onNavigateToSongDetails = { songId ->
                        navController.navigate(Screen.SongDetails.createRoute(songId))
                    },
                    onNavigateToDownloads = {
                        navController.navigateToTopLevel(Screen.Downloads)
                    },
                    onNavigateToLibrary = {
                        navController.navigateToTopLevel(Screen.Library)
                    }
                )
            }

            composable(
                route = Screen.Search.pattern,
                arguments = listOf(
                    navArgument("query") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) { backStackEntry ->
                val initialQuery = backStackEntry.arguments?.getString("query")
                SearchScreen(
                    initialQuery = initialQuery,
                    onNavigateToSongDetails = { songId ->
                        navController.navigate(Screen.SongDetails.createRoute(songId))
                    }
                )
            }

            // Retain the plain destination so saved stacks from previous versions remain valid.
            composable(Screen.Search.route) {
                SearchScreen(onNavigateToSongDetails = { songId ->
                    navController.navigate(Screen.SongDetails.createRoute(songId))
                })
            }

            composable(Screen.Downloads.route) {
                DownloadsScreen(
                    onNavigateToSearch = {
                        navController.navigateToTopLevel(Screen.Search)
                    }
                )
            }

            composable(Screen.Library.route) {
                LibraryScreen(
                    onNavigateToPlaylist = { playlistId ->
                        navController.navigate(Screen.PlaylistDetails.createRoute(playlistId))
                    },
                    onNavigateToSearch = {
                        navController.navigateToTopLevel(Screen.Search)
                    }
                )
            }

            composable(Screen.Settings.route) {
                SettingsScreen()
            }

            composable(
                route = Screen.SongDetails.route,
                arguments = listOf(navArgument("songId") { type = NavType.StringType })
            ) { backStackEntry ->
                val songId = backStackEntry.arguments?.getString("songId") ?: ""
                SongDetailsScreen(
                    songId = songId,
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(
                route = Screen.PlaylistDetails.route,
                arguments = listOf(navArgument("playlistId") { type = NavType.StringType })
            ) { backStackEntry ->
                val playlistId = backStackEntry.arguments?.getString("playlistId") ?: ""
                PlaylistDetailsScreen(
                    playlistId = playlistId,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }
    }
}
