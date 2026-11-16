package com.seslikitap

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.seslikitap.ui.MiniPlayer
import com.seslikitap.ui.home.HomeScreen
import com.seslikitap.ui.stats.StatsScreen
import androidx.navigation.navArgument
import com.seslikitap.ui.book.BookDetailScreen
import com.seslikitap.ui.folders.DrivePickerScreen
import com.seslikitap.ui.folders.FoldersScreen
import com.seslikitap.ui.library.LibraryScreen
import com.seslikitap.ui.reader.ReaderScreen
import com.seslikitap.ui.theme.SesliKitapTheme

class MainActivity : ComponentActivity() {

    /** Bildirime dokunulunca okuma ekranını aç. */
    private val openPlayer = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        setContent {
            SesliKitapTheme {
                AppNavigation(openPlayer)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) == true) openPlayer.value = true
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "open_player"
    }
}

private data class Tab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val TABS = listOf(
    Tab("home", "Ana Sayfa", Icons.Default.Home),
    Tab("library", "Kitaplık", Icons.AutoMirrored.Filled.LibraryBooks),
    Tab("huzur", "Huzur", Icons.Default.Spa),
    Tab("stats", "İstatistik", Icons.Default.BarChart),
    Tab("settings", "Ayarlar", Icons.Default.Settings),
)

@Composable
private fun AppNavigation(openPlayer: MutableState<Boolean>) {
    val nav = rememberNavController()
    val player = LocalContext.current.container.player
    val playerState by player.state.collectAsStateWithLifecycle()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val onTab = TABS.any { it.route == route }

    fun goTab(r: String) = nav.navigate(r) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
    val openReader: (Long) -> Unit = { id -> nav.navigate("reader/$id") { launchSingleTop = true } }
    val openBook: (Long) -> Unit = { id -> nav.navigate("book/$id") }

    LaunchedEffect(openPlayer.value) {
        if (openPlayer.value) {
            openPlayer.value = false
            player.state.value.bookId?.let(openReader)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (onTab) {
                Column {
                    if (playerState.active) {
                        MiniPlayer(
                            playerState,
                            onClick = { playerState.bookId?.let(openReader) },
                            onToggle = { player.toggle() },
                        )
                    }
                    NavigationBar {
                        TABS.forEach { t ->
                            NavigationBarItem(
                                selected = route == t.route,
                                onClick = { goTab(t.route) },
                                icon = { Icon(t.icon, null) },
                                label = { Text(t.label) },
                            )
                        }
                    }
                }
            }
        },
    ) { outer ->
        NavHost(navController = nav, startDestination = "home", modifier = Modifier.padding(outer)) {
            composable("home") {
                HomeScreen(
                    onOpenBook = openBook,
                    onOpenReader = openReader,
                    onOpenLibraryFolder = { goTab("library") },
                    onOpenSettings = { goTab("settings") },
                    onOpenQuotes = { nav.navigate("quotes") },
                    onOpenTool = { kind, mood -> nav.navigate("tools/$kind" + (mood?.let { "?mood=${android.net.Uri.encode(it)}" } ?: "")) },
                )
            }
            composable("quotes") {
                com.seslikitap.ui.quotes.QuotesScreen(onBack = { nav.popBackStack() }, onOpenBook = openBook)
            }
            composable("library") {
                LibraryScreen(
                    onOpenBook = openBook,
                    onOpenReader = openReader,
                    onOpenFolders = { goTab("settings") },
                    onAddDrive = { nav.navigate("drive") },
                )
            }
            composable("huzur") {
                com.seslikitap.ui.huzur.HuzurScreen(onOpen = { kind -> nav.navigate("huzur/$kind") })
            }
            composable(
                "huzur/{kind}",
                arguments = listOf(navArgument("kind") { type = NavType.StringType }),
            ) { entry ->
                com.seslikitap.ui.huzur.HuzurDetail(
                    kind = entry.arguments?.getString("kind") ?: "",
                    onBack = { nav.popBackStack() },
                    onOpenBook = openBook,
                    onOpenReader = openReader,
                )
            }
            composable("stats") {
                StatsScreen(onOpenBook = openBook)
            }
            composable("settings") {
                FoldersScreen(onBack = null, onAddDrive = { nav.navigate("drive") })
            }
            composable(
                "book/{id}",
                arguments = listOf(navArgument("id") { type = NavType.LongType }),
            ) { entry ->
                val id = entry.arguments?.getLong("id") ?: 0L
                BookDetailScreen(
                    bookId = id,
                    onBack = { nav.popBackStack() },
                    onListen = { openReader(id) },
                )
            }
            composable(
                "reader/{id}",
                arguments = listOf(navArgument("id") { type = NavType.LongType }),
            ) { entry ->
                ReaderScreen(
                    bookId = entry.arguments?.getLong("id") ?: 0L,
                    onBack = { nav.popBackStack() },
                    onCalm = { ritual -> nav.navigate("calm?ritual=$ritual") },
                    onCar = { nav.navigate("car") },
                )
            }
            composable(
                "calm?ritual={ritual}",
                arguments = listOf(navArgument("ritual") { type = NavType.BoolType; defaultValue = false }),
            ) { entry ->
                com.seslikitap.ui.calm.CalmScreen(
                    ritual = entry.arguments?.getBoolean("ritual") ?: false,
                    onExit = { nav.popBackStack() },
                )
            }
            composable(
                "tools/{kind}?mood={mood}",
                arguments = listOf(
                    navArgument("kind") { type = NavType.StringType },
                    navArgument("mood") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) { entry ->
                com.seslikitap.ui.tools.ToolsScreen(
                    kind = entry.arguments?.getString("kind") ?: "",
                    mood = entry.arguments?.getString("mood"),
                    onBack = { nav.popBackStack() },
                    onOpenBook = openBook,
                )
            }
            composable("car") {
                com.seslikitap.ui.calm.CarScreen(onExit = { nav.popBackStack() })
            }
            composable("drive") {
                DrivePickerScreen(
                    onBack = { nav.popBackStack() },
                    onAdded = { nav.popBackStack(); goTab("library") },
                )
            }
        }
    }
}
