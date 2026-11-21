package com.ziribrowser.ui.nav

import com.ziribrowser.ziriString
import com.ziribrowser.R
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ziribrowser.ui.GlassBackground
import com.ziribrowser.ui.GlassSurface
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tab
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tab
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import com.ziribrowser.ui.LocalBarClearance
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ziribrowser.AppContainer
import com.ziribrowser.ui.bookmarks.BookmarksScreen
import com.ziribrowser.ui.browser.BrowserRoute
import com.ziribrowser.ui.downloads.DownloadsScreen
import com.ziribrowser.ui.history.HistoryScreen
import com.ziribrowser.ui.home.HomeScreen
import com.ziribrowser.ui.settings.SettingsPage
import com.ziribrowser.ui.settings.SettingsScreen
import com.ziribrowser.ui.tabs.TabsScreen
import com.ziribrowser.ui.viewer.ViewerScreen
import com.ziribrowser.ui.viewer.MxPlayer
import com.ziribrowser.ui.files.FilesScreen

private data class Destination(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val destinations get() = listOf(
    Destination("home", ziriString(R.string.home), Icons.Rounded.Home, Icons.Rounded.Home),
    Destination("bookmarks", ziriString(R.string.bookmarks), Icons.Rounded.BookmarkBorder, Icons.Rounded.Bookmark),
    Destination("tabs", ziriString(R.string.tabs), Icons.Rounded.Tab, Icons.Rounded.Tab),
    Destination("downloads", ziriString(R.string.downloads), Icons.Rounded.FileDownload, Icons.Rounded.Download),
    Destination("settings", ziriString(R.string.settings), Icons.Rounded.Settings, Icons.Rounded.Settings),
)

@Composable
fun ZiriNavHost(container: AppContainer) {
    val nav = rememberNavController()
    // Back arrows must never pop the start destination: a second quick tap during the exit animation would leave an empty screen.
    val back: () -> Unit = { if (nav.previousBackStackEntry != null) nav.popBackStack() }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val tabs by container.tabs.tabs.collectAsStateWithLifecycle()

    // Other parts of the app (an opened link, a notification tap) ask for a screen here.
    LaunchedEffect(Unit) {
        container.navRequests.collect { r -> nav.navigate(r) { launchSingleTop = true } }
    }

    fun openBrowser() = nav.navigate("browser") { popUpTo("home"); launchSingleTop = true }

    val onboardingDone by container.onboardingDone.collectAsStateWithLifecycle(true)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val showBar = onboardingDone && route != null && route != "browser" && route?.startsWith("viewer") != true && route?.startsWith("play") != true
    val hazeState = rememberHazeState()
    val barClearance = if (showBar) 92.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() else 0.dp

    val wallpaper by container.settings.wallpaper.collectAsStateWithLifecycle(com.ziribrowser.data.WallpaperConfig())
    val photo by com.ziribrowser.ui.rememberWallpaperPhoto(
        androidx.compose.ui.platform.LocalContext.current, wallpaper.stamp, wallpaper.kind == com.ziribrowser.data.WallpaperKind.CUSTOM,
    )
    // "Home only" shows the picture on the home screen alone; "Everywhere" on every screen except the browser.
    val wallpaperActive = wallpaper.everywhere || route == "home" || route == null

    GlassBackground(wallpaper = wallpaper, photo = photo, wallpaperActive = wallpaperActive) {
        Surface(color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onBackground, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                // Screens draw behind the floating bar; the bar blurs whatever scrolls under it.
                CompositionLocalProvider(LocalBarClearance provides barClearance) {
                    NavHost(
                        navController = nav, startDestination = "home",
                        modifier = Modifier.fillMaxSize().hazeSource(hazeState),
                        enterTransition = { enter(targetState.destination.route) },
                        exitTransition = { exit(initialState.destination.route) },
                        popEnterTransition = { enter(targetState.destination.route) },
                        popExitTransition = { exit(initialState.destination.route) },
                    ) {
                        composable("home") {
                            HomeScreen(
                                container,
                                onOpenBrowser = { openBrowser() },
                                onNavigate = { nav.navigate(it) { launchSingleTop = true } },
                            )
                        }
                        composable("browser") {
                            BrowserRoute(
                                container,
                                // Back to Home. If Home isn't in the back stack (e.g. the app was opened straight into the browser
                                // by a link), go to it directly so the button always works.
                                onClose = {
                                    if (!nav.popBackStack("home", inclusive = false)) {
                                        nav.navigate("home") { popUpTo(nav.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true }
                                    }
                                },
                                onNavigate = { nav.navigate(it) { launchSingleTop = true } },
                            )
                        }
                        composable("tabs") {
                            TabsScreen(container, onOpenBrowser = { openBrowser() }, onHome = {
                                if (!nav.popBackStack("home", inclusive = false)) nav.navigate("home") { launchSingleTop = true }
                            })
                        }
                        composable("bookmarks") { BookmarksScreen(container, onOpenBrowser = { openBrowser() }) }
                        composable("history") { HistoryScreen(container, onBack = back, onOpenBrowser = { openBrowser() }) }
                        composable("downloads") {
                            DownloadsScreen(container, onOpenViewer = { nav.navigate("viewer/$it") { launchSingleTop = true } }, onOpenFiles = { nav.navigate("files") { launchSingleTop = true } })
                        }
                        composable("files") {
                            FilesScreen(
                                container,
                                onPlay = { uri, name -> nav.navigate("play/${android.net.Uri.encode(uri.toString())}?title=${android.net.Uri.encode(name)}") { launchSingleTop = true } },
                                onOpenViewer = { nav.navigate("viewer/$it") { launchSingleTop = true } },
                                onBack = back,
                            )
                        }
                        composable("play/{uri}?title={title}") { backStack ->
                            val uri = android.net.Uri.parse(backStack.arguments?.getString("uri").orEmpty())
                            val name = backStack.arguments?.getString("title").orEmpty().ifEmpty { uri.lastPathSegment.orEmpty() }
                            val queue = remember(uri) {
                                // The folder's list belongs to the tap that set it; consume it so a later "open with Ziri" of a file in that folder doesn't inherit it.
                                val pending = container.pendingQueue
                                container.pendingQueue = null
                                pending?.takeIf { q -> q.items.any { it.uri == uri } }?.let { q -> q.copy(start = q.items.indexOfFirst { it.uri == uri }.coerceAtLeast(0)) }
                                    ?: com.ziribrowser.ui.viewer.PlayQueue(listOf(com.ziribrowser.ui.viewer.PlayItem(uri, name, "local:$uri")))
                            }
                            MxPlayer(container, queue, onBack = back)
                        }
                        composable("viewer/{id}") { backStack ->
                            ViewerScreen(container, backStack.arguments?.getString("id")?.toLongOrNull() ?: -1L, onBack = back)
                        }
                        composable("settings") {
                            SettingsScreen(container, onNavigate = { nav.navigate(it) { launchSingleTop = true } })
                        }
                        composable("settings/{page}") { backStack ->
                            SettingsPage(container, backStack.arguments?.getString("page").orEmpty(), onBack = back)
                        }
                    }
                }

                if (!onboardingDone) com.ziribrowser.ui.onboarding.OnboardingScreen { picked ->
                    scope.launch { container.settings.finishOnboarding(picked) }
                }

                AnimatedVisibility(
                    visible = showBar,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    enter = slideInVertically(tween(320)) { it } + fadeIn(tween(320)),
                    exit = slideOutVertically(tween(220)) { it } + fadeOut(tween(180)),
                ) {
                    ZiriNavBar(
                        items = destinations.map { d -> NavItem(d.route, d.label, d.icon, badge = if (d.route == "tabs") tabs.size else 0) },
                        // A settings sub-page still counts as the Settings tab.
                        selectedRoute = if (route?.startsWith("settings") == true) "settings" else route,
                        haze = hazeState,
                        onSelect = { target ->
                            if (target == "settings" && route?.startsWith("settings/") == true) {
                                nav.popBackStack("settings", inclusive = false) // back to the Settings home
                            } else if (target == "home") {
                                // Always go straight back to Home (no saved-state restoring, which could leave you stuck).
                                if (!nav.popBackStack("home", inclusive = false)) nav.navigate("home") { launchSingleTop = true }
                            } else if (target != route) nav.navigate(target) {
                                popUpTo("home") { inclusive = false }
                                launchSingleTop = true
                            }
                        },
                        modifier = Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

private val smooth = FastOutSlowInEasing

/** The browser rises from below, History slides in from the side, tab-to-tab switches cross-fade. */
private fun enter(to: String?): EnterTransition = when (to) {
    "browser" -> slideInVertically(tween(380, easing = smooth)) { it / 10 } + fadeIn(tween(300))
    "history", "files" -> slideInHorizontally(tween(360, easing = smooth)) { it / 5 } + fadeIn(tween(300))
    else -> fadeIn(tween(300, delayMillis = 60)) + scaleIn(tween(300, delayMillis = 60, easing = smooth), initialScale = 0.96f)
}

private fun exit(from: String?): ExitTransition = when (from) {
    "browser" -> slideOutVertically(tween(300, easing = smooth)) { it / 10 } + fadeOut(tween(220))
    "history", "files" -> slideOutHorizontally(tween(300, easing = smooth)) { it / 5 } + fadeOut(tween(220))
    else -> fadeOut(tween(180))
}
