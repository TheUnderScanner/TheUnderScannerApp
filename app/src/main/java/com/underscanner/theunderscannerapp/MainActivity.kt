package com.underscanner.theunderscannerapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
        hideStatusBar()
        // Already-granted users: move any scans left in the old app-private folder to the public
        // one. Off the main thread since .pcd files can be tens of MB. Idempotent + no-op if empty.
        if (Environment.isExternalStorageManager()) {
            Thread { LocalScanStorage.migrateLegacyScans(applicationContext) }.start()
        }
        setContent {
            TheUnderScannerAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavHost()
                    StoragePermissionPrompt()
                }
            }
        }
    }

    /**
     * Hide Android's status bar (clock, battery, notification icons) app-wide.
     *
     * The phone is this device's only screen and is used one-handed underground, so the
     * system bar was overlapping app chrome for no benefit. A swipe from the top still
     * reveals it transiently (BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE) — deliberately kept,
     * because phone battery matters on a long trip and hiding it outright would remove the
     * only way to check it without leaving the app.
     *
     * `decorFitsSystemWindows` is intentionally left alone: hiding the bar already zeroes
     * its inset, and going edge-to-edge would push content under the navigation bar on
     * every existing screen for no gain here.
     */
    private fun hideStatusBar() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Android restores the bars after permission dialogs, app switches and the like,
        // so re-assert on every focus gain instead of only at creation.
        if (hasFocus) hideStatusBar()
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
                onOpenHealth = { scanName -> navController.navigate("healthChart/$scanName") },
                onOpenSettings = { navController.navigate("settings") }
            )
        }

        composable(
            route = "healthChart/{scanName}",
            arguments = listOf(navArgument("scanName") { type = NavType.StringType })
        ) { backStackEntry ->
            HealthChartScreen(
                scanName = backStackEntry.arguments?.getString("scanName") ?: "",
                onBack = { navController.popBackStack() }
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

/**
 * One-time prompt asking for All-files access so downloaded scans can be written to the public
 * `Documents/UnderScanner/Scans` folder. Shown only while the permission is missing; sends the
 * user to the system toggle and, on return, re-checks and migrates any legacy app-private scans.
 * Dismissible ("Plus tard") so the rest of the app (settings, live preview) stays reachable.
 */
@Composable
fun StoragePermissionPrompt() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    var dismissed by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // The all-files-access screen always returns RESULT_CANCELED; re-check the real state.
        val now = Environment.isExternalStorageManager()
        if (now && !granted) LocalScanStorage.migrateLegacyScans(context)
        granted = now
    }

    if (granted || dismissed) return

    AlertDialog(
        onDismissRequest = { dismissed = true },
        title = { Text("Accès aux fichiers") },
        text = {
            Text(
                "Pour enregistrer les scans dans Stockage interne/Documents/UnderScanner/Scans " +
                    "(visibles dans l'appli Fichiers), l'application a besoin de l'accès à tous les fichiers."
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
                runCatching { launcher.launch(intent) }.onFailure {
                    // Some OEMs don't support the per-app screen; open the generic list.
                    runCatching {
                        launcher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                }
            }) { Text("Autoriser") }
        },
        dismissButton = {
            TextButton(onClick = { dismissed = true }) { Text("Plus tard") }
        }
    )
}
