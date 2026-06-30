package com.underscanner.theunderscannerapp

import android.widget.Toast
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
        }
    }
}
