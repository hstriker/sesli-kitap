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

@Composable
private fun AppNavigation(openPlayer: MutableState<Boolean>) {
    val nav = rememberNavController()
    val player = androidx.compose.ui.platform.LocalContext.current.container.player

    LaunchedEffect(openPlayer.value) {
        if (openPlayer.value) {
            openPlayer.value = false
            player.state.value.bookId?.let { id ->
                nav.navigate("reader/$id") { launchSingleTop = true }
            }
        }
    }

    NavHost(navController = nav, startDestination = "library") {
        composable("library") {
            LibraryScreen(
                onOpenBook = { id -> nav.navigate("book/$id") },
                onOpenReader = { id -> nav.navigate("reader/$id") { launchSingleTop = true } },
                onOpenFolders = { nav.navigate("folders") },
                onAddDrive = { nav.navigate("drive") },
            )
        }
        composable(
            "book/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { entry ->
            val id = entry.arguments?.getLong("id") ?: 0L
            BookDetailScreen(
                bookId = id,
                onBack = { nav.popBackStack() },
                onListen = { nav.navigate("reader/$id") { launchSingleTop = true } },
            )
        }
        composable(
            "reader/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { entry ->
            ReaderScreen(
                bookId = entry.arguments?.getLong("id") ?: 0L,
                onBack = { nav.popBackStack() },
            )
        }
        composable("folders") {
            FoldersScreen(onBack = { nav.popBackStack() }, onAddDrive = { nav.navigate("drive") })
        }
        composable("drive") {
            DrivePickerScreen(
                onBack = { nav.popBackStack() },
                onAdded = { nav.popBackStack("library", inclusive = false) },
            )
        }
    }
}
