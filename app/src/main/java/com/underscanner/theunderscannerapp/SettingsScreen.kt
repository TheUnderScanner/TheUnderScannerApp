package com.underscanner.theunderscannerapp

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions

/**
 * Settings: edit the Jetson base URL. The hotspot-assigned IP changes between
 * sessions, so this is the main thing the user adjusts when reconnecting.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: ScanLibraryViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val connection by viewModel.connection
    var url by remember { mutableStateOf(viewModel.baseUrl) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Réglages") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Adresse du Jetson", style = MaterialTheme.typography.titleMedium)
            Text(
                "Hôte et port du serveur FastAPI. L'IP attribuée par le hotspot change : adaptez-la ici.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("URL de base") },
                placeholder = { Text(SettingsRepository.DEFAULT_BASE_URL) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    val normalized = SettingsRepository.normalize(url)
                    viewModel.updateBaseUrl(normalized)
                    url = normalized
                    Toast.makeText(context, "Connexion à $normalized…", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Enregistrer et se connecter")
            }

            HorizontalDivider()

            // Live connection state for the entered address
            val (color, label) = when (val c = connection) {
                is ConnectionState.Connected ->
                    MaterialTheme.colorScheme.primary to "Connecté à ${c.status.hostname.ifBlank { "Jetson" }} (v${c.status.version})"
                is ConnectionState.Connecting -> MaterialTheme.colorScheme.tertiary to "Connexion…"
                is ConnectionState.Offline -> MaterialTheme.colorScheme.error to "Hors-ligne : ${c.reason}"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(color, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.bodyMedium)
            }

            HorizontalDivider()

            // --- Storage / All-files access ---------------------------------------------------
            var storageGranted by remember { mutableStateOf(Environment.isExternalStorageManager()) }
            val storageLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) {
                // The all-files-access screen always returns RESULT_CANCELED; re-check real state.
                val now = Environment.isExternalStorageManager()
                if (now && !storageGranted) LocalScanStorage.migrateLegacyScans(context)
                storageGranted = now
            }

            Text("Stockage des scans", style = MaterialTheme.typography.titleMedium)
            Text(
                "Les scans téléchargés sont enregistrés dans " +
                    "Stockage interne/Documents/UnderScanner/Scans (visibles dans l'appli Fichiers).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(12.dp).background(
                        if (storageGranted) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                        CircleShape
                    )
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (storageGranted) "Accès aux fichiers autorisé"
                    else "Accès aux fichiers non autorisé",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            if (!storageGranted) {
                Button(
                    onClick = {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        runCatching { storageLauncher.launch(intent) }.onFailure {
                            // Some OEMs don't support the per-app screen; open the generic list.
                            runCatching {
                                storageLauncher.launch(
                                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Autoriser l'accès aux fichiers")
                }
            }

            // Best-effort "open in file manager": the stock Files app (DocumentsUI) answers an
            // ACTION_VIEW on a directory document URI. No universal intent exists, so fall back to
            // just showing the path if no app handles it.
            OutlinedButton(
                onClick = {
                    LocalScanStorage.scansDir(context) // make sure the folder exists to navigate into
                    val treeUri = DocumentsContract.buildDocumentUri(
                        "com.android.externalstorage.documents",
                        "primary:Documents/UnderScanner/Scans"
                    )
                    val open = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(treeUri, DocumentsContract.Document.MIME_TYPE_DIR)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching { context.startActivity(open) }.onFailure {
                        Toast.makeText(
                            context,
                            "Dossier : Stockage interne/Documents/UnderScanner/Scans",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Ouvrir le dossier des scans")
            }
        }
    }
}
