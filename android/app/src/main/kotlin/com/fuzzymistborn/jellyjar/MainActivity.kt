package com.fuzzymistborn.jellyjar

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.fuzzymistborn.jellyjar.ui.screens.*
import com.fuzzymistborn.jellyjar.ui.theme.JellyJarTheme
import dagger.hilt.android.AndroidEntryPoint

sealed class Screen(val route: String) {
    object Library : Screen("library")
    object Detail : Screen("detail/{itemId}") {
        fun go(itemId: String) = "detail/$itemId"
    }
    object Series : Screen("series/{seriesId}") {
        fun go(seriesId: String) = "series/$seriesId"
    }
    object Player : Screen("player/{localPath}/{jellyfinId}?startMs={startMs}") {
        fun go(localPath: String, jellyfinId: String = "", startMs: Long = 0L) =
            "player/${localPath.encode()}/${jellyfinId.encode()}?startMs=$startMs"
    }
    object StreamPlayer : Screen("stream/{streamUrl}/{jellyfinId}?startMs={startMs}") {
        fun go(streamUrl: String, jellyfinId: String = "", startMs: Long = 0L) =
            "stream/${streamUrl.encode()}/${jellyfinId.encode()}?startMs=$startMs"
    }
    object Season : Screen("season/{seasonId}/{seriesId}") {
        fun go(seasonId: String, seriesId: String) = "season/$seasonId/$seriesId"
    }
    object Downloads : Screen("downloads")
    object PinGate : Screen("pin_gate")
    object Admin : Screen("admin")
}

private fun String.encode() = java.net.URLEncoder.encode(this, "UTF-8")
private fun String.decode() = java.net.URLDecoder.decode(this, "UTF-8")

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* results ignored — app still runs; playback will fail gracefully without the permission */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestMediaPermissionsIfNeeded()
        setContent {
            JellyJarTheme {
                JellyJarNavHost()
            }
        }
    }

    private fun requestMediaPermissionsIfNeeded() {
        val needed = listOf(
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.POST_NOTIFICATIONS,
        ).filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) requestPermissions.launch(needed.toTypedArray())
    }
}

@Composable
fun JellyJarNavHost() {
    val navController = rememberNavController()
    var isAdminUnlocked by rememberSaveable { mutableStateOf(false) }

    NavHost(
        navController = navController,
        startDestination = Screen.Library.route,
        enterTransition = { fadeIn(tween(300)) + slideInHorizontally(tween(300)) { it / 5 } },
        exitTransition = { fadeOut(tween(200)) + slideOutHorizontally(tween(200)) { -it / 5 } },
        popEnterTransition = { fadeIn(tween(300)) + slideInHorizontally(tween(300)) { -it / 5 } },
        popExitTransition = { fadeOut(tween(200)) + slideOutHorizontally(tween(200)) { it / 5 } },
    ) {

        composable(Screen.Library.route) {
            LibraryScreen(
                onItemClick = { item ->
                    navController.navigate(Screen.Detail.go(item.id))
                },
                onPlayOffline = { localPath ->
                    navController.navigate(Screen.Player.go(localPath, ""))
                },
                onAdminClick = {
                    navController.navigate(Screen.PinGate.route)
                },
                onDownloadsClick = {
                    navController.navigate(Screen.Downloads.route)
                },
            )
        }

        composable(Screen.Downloads.route) {
            com.fuzzymistborn.jellyjar.ui.screens.DownloadsScreen(
                onPlayClick = { localPath, jellyfinId ->
                    navController.navigate(Screen.Player.go(localPath, jellyfinId))
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Screen.Detail.route,
            arguments = listOf(navArgument("itemId") { type = NavType.StringType }),
        ) { backStack ->
            val itemId = backStack.arguments?.getString("itemId") ?: return@composable
            DetailScreen(
                itemId = itemId,
                onPlayClick = { localPath, jellyfinId, startMs ->
                    navController.navigate(Screen.Player.go(localPath, jellyfinId, startMs))
                },
                onStreamClick = { streamUrl, jellyfinId, startMs ->
                    navController.navigate(Screen.StreamPlayer.go(streamUrl, jellyfinId, startMs))
                },
                onEpisodeClick = { episodeId ->
                    navController.navigate(Screen.Detail.go(episodeId))
                },
                onSeasonClick = { seasonId, seriesId ->
                    navController.navigate(Screen.Season.go(seasonId, seriesId))
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Screen.Season.route,
            arguments = listOf(
                navArgument("seasonId") { type = NavType.StringType },
                navArgument("seriesId") { type = NavType.StringType },
            ),
        ) { backStack ->
            val seasonId = backStack.arguments?.getString("seasonId") ?: return@composable
            val seriesId = backStack.arguments?.getString("seriesId") ?: return@composable
            SeasonScreen(
                seasonId = seasonId,
                seriesId = seriesId,
                onEpisodeClick = { episodeId ->
                    navController.navigate(Screen.Detail.go(episodeId))
                },
                onPlayClick = { localPath, jellyfinId, startMs ->
                    navController.navigate(Screen.Player.go(localPath, jellyfinId, startMs))
                },
                onStreamClick = { streamUrl, jellyfinId, startMs ->
                    navController.navigate(Screen.StreamPlayer.go(streamUrl, jellyfinId, startMs))
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Screen.Series.route,
            arguments = listOf(navArgument("seriesId") { type = NavType.StringType }),
        ) { backStack ->
            val seriesId = backStack.arguments?.getString("seriesId") ?: return@composable
            com.fuzzymistborn.jellyjar.ui.screens.SeasonsScreen(
                seriesId = seriesId,
                onEpisodeClick = { streamUrl ->
                    navController.navigate(Screen.StreamPlayer.go(streamUrl))
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Screen.StreamPlayer.route,
            arguments = listOf(
                navArgument("streamUrl") { type = NavType.StringType },
                navArgument("jellyfinId") { type = NavType.StringType },
                navArgument("startMs") { type = NavType.LongType; defaultValue = 0L },
            ),
        ) { backStack ->
            val streamUrl = backStack.arguments?.getString("streamUrl")?.decode() ?: return@composable
            val jellyfinId = backStack.arguments?.getString("jellyfinId")?.decode()?.takeIf { it.isNotBlank() }
            val startMs = backStack.arguments?.getLong("startMs") ?: 0L
            PlayerScreen(
                localPath = streamUrl,
                jellyfinId = jellyfinId,
                startPositionMs = startMs,
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Screen.Player.route,
            arguments = listOf(
                navArgument("localPath") { type = NavType.StringType },
                navArgument("jellyfinId") { type = NavType.StringType },
                navArgument("startMs") { type = NavType.LongType; defaultValue = 0L },
            ),
        ) { backStack ->
            val localPath = backStack.arguments?.getString("localPath")?.decode() ?: return@composable
            val jellyfinId = backStack.arguments?.getString("jellyfinId")?.decode()?.takeIf { it.isNotBlank() }
            val startMs = backStack.arguments?.getLong("startMs") ?: 0L
            PlayerScreen(
                localPath = localPath,
                jellyfinId = jellyfinId,
                startPositionMs = startMs,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Screen.PinGate.route) {
            val adminViewModel: com.fuzzymistborn.jellyjar.ui.viewmodel.AdminViewModel = androidx.hilt.navigation.compose.hiltViewModel()
            val adminState by adminViewModel.state.collectAsStateWithLifecycle()
            PinGateScreen(
                isPinEnabled = adminState.isPinEnabled,
                settingsLoaded = adminState.settingsLoaded,
                onUnlocked = {
                    isAdminUnlocked = true
                    navController.navigate(Screen.Admin.route) {
                        popUpTo(Screen.PinGate.route) { inclusive = true }
                    }
                },
                onSkip = {
                    isAdminUnlocked = true
                    navController.navigate(Screen.Admin.route) {
                        popUpTo(Screen.PinGate.route) { inclusive = true }
                    }
                },
            )
        }

        composable(Screen.Admin.route) {
            AdminScreen(
                onBack = {
                    isAdminUnlocked = false
                    navController.popBackStack()
                },
            )
        }
    }
}
