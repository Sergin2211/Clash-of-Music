package com.clashofmusic.app.presentation.navigation

import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController

internal fun String?.topLevelRoute(): String? = this?.substringBefore('?')

/** Tab changes must not save Search as a child of Downloads/Library. */
internal fun NavHostController.navigateToTopLevel(screen: Screen, query: String? = null) {
    require(screen in Screen.bottomNavScreens)
    val route = if (screen == Screen.Search) Screen.Search.createRoute(query) else screen.route
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = query == null
    }
    // Older versions may have already saved Downloads -> Search as one stack.
    // Unwind that saved child instead of opening the wrong tab on its next visit.
    if (currentDestination?.route.topLevelRoute() != screen.route) {
        if (!popBackStack(screen.route, inclusive = false)) {
            navigate(route) {
                popUpTo(graph.findStartDestination().id)
                launchSingleTop = true
                restoreState = false
            }
        }
    }
}
