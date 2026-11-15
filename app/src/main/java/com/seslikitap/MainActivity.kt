package com.seslikitap

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.seslikitap.ui.book.BookDetailScreen
import com.seslikitap.ui.folders.FoldersScreen
import com.seslikitap.ui.library.LibraryScreen
import com.seslikitap.ui.theme.SesliKitapTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SesliKitapTheme {
                AppNavigation()
            }
        }
    }
}

@Composable
private fun AppNavigation() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "library") {
        composable("library") {
            LibraryScreen(
                onOpenBook = { id -> nav.navigate("book/$id") },
                onOpenFolders = { nav.navigate("folders") },
            )
        }
        composable(
            "book/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { entry ->
            BookDetailScreen(
                bookId = entry.arguments?.getLong("id") ?: 0L,
                onBack = { nav.popBackStack() },
            )
        }
        composable("folders") {
            FoldersScreen(onBack = { nav.popBackStack() })
        }
    }
}
