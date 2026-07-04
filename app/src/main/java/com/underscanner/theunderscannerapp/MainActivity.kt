package com.underscanner.theunderscannerapp

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.underscanner.theunderscannerapp.ui.theme.TheUnderScannerAppTheme

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TheUnderScannerAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavHost()
                }
            }
        }
    }
}

/**
 * App navigation:
 *   mainMenu (start) → scanLibrary (Phase 1) / controlRoom (Phase 2) / settings
 *   controlRoom → activeScan (live preview)
 *   scanLibrary → pcdViewer
 *
 * Two shared ViewModels (library + control) so state survives navigation between screens.
 */
@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    val libraryViewModel: ScanLibraryViewModel = viewModel()
    val controlViewModel: ScanControlViewModel = viewModel()

    NavHost(navController = navController, startDestination = "mainMenu") {
        composable("mainMenu") {
            MainMenuScreen(
                onOpenLibrary = { navController.navigate("scanLibrary") },
                onOpenControlRoom = { navController.navigate("controlRoom") },
                onOpenSettings = { navController.navigate("settings") }
            )
        }

        composable(
            route = "scanLibrary?openNotes={openNotes}",
            arguments = listOf(navArgument("openNotes") { type = NavType.StringType; defaultValue = "" })
        ) { backStackEntry ->
            val openNotes = backStackEntry.arguments?.getString("openNotes").orEmpty()
            ScanLibraryScreen(
                viewModel = libraryViewModel,
                openNotesFor = openNotes.ifBlank { null },
                onOpenPcd = { scanName -> navController.navigate("pcdViewer/$scanName.pcd") },
                onOpenSettings = { navController.navigate("settings") }
            )
        }

        composable("settings") {
            SettingsScreen(viewModel = libraryViewModel, onBack = { navController.popBackStack() })
        }

        composable(
            route = "pcdViewer/{fileName}",
            arguments = listOf(navArgument("fileName") { type = NavType.StringType })
        ) { backStackEntry ->
            val fileName = backStackEntry.arguments?.getString("fileName") ?: ""
            PCDViewerScreen(fileName = fileName)
        }

        composable("controlRoom") {
            ControlRoomScreen(
                viewModel = controlViewModel,
                onBack = { navController.popBackStack() },
                onOpenSettings = { navController.navigate("settings") },
                onOpenActive = { navController.navigate("activeScan") }
            )
        }

        composable("activeScan") {
            ActiveScanScreen(
                viewModel = controlViewModel,
                onExit = { navController.popBackStack() },
                onGoToLibrary = {
                    navController.navigate("scanLibrary") {
                        popUpTo("mainMenu")
                    }
                },
                onAddNotes = { scanName ->
                    navController.navigate("scanLibrary?openNotes=$scanName") {
                        popUpTo("mainMenu")
                    }
                }
            )
        }
    }
}
