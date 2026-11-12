package com.music.spotui.ui.tv

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.music.spotui.ui.navigation.Routes
import com.music.spotui.ui.screens.ExtensionEntryScreen
import com.music.spotui.ui.screens.ExtensionPageScreen
import com.music.spotui.ui.screens.ExtensionScreen
import com.music.spotui.ui.screens.ExtensionsScreen

/**
 * Extensions on the TV: the store, the installed ones, an extension's page and its settings, the
 * same screens as on a phone (their rows take the remote's focus as any clickable does), in a
 * navigation of their own under the TV's tab. An audio-source extension installed here plays on
 * the TV as it does on a phone: the player is the same.
 */
@Composable
internal fun TvExtensions() {
    val nav = rememberNavController()
    val id = listOf(navArgument("id") { type = NavType.StringType; defaultValue = "" })
    NavHost(nav, startDestination = Routes.Extensions.route) {
        composable(Routes.Extensions.route) { ExtensionsScreen(nav) }
        composable("${Routes.Extension.route}/{id}", arguments = id) { ExtensionScreen(nav, it.arguments?.getString("id").orEmpty()) }
        composable("${Routes.ExtensionEntry.route}/{id}", arguments = id) { ExtensionEntryScreen(nav, it.arguments?.getString("id").orEmpty()) }
        composable("extension-page/{id}", arguments = id) { ExtensionPageScreen(nav, it.arguments?.getString("id").orEmpty()) }
    }
}
