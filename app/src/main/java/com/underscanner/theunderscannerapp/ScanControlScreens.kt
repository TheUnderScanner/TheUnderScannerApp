package com.underscanner.theunderscannerapp

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

private fun formatElapsed(s: Long): String = "%d:%02d".format(s / 60, s % 60)

// ===========================================================================
// Control Room — entry point for starting / resuming a scan
// ===========================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlRoomScreen(
    viewModel: ScanControlViewModel,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenActive: () -> Unit
) {
    val context = LocalContext.current
    val connection by viewModel.connection
    val scanStatus by viewModel.scanStatus
    val shuttingDown by viewModel.shuttingDown

    DisposableEffect(Unit) {
        viewModel.startPolling()
        onDispose { viewModel.stopPolling() }
    }

    val isConnected = connection is ConnectionState.Connected
    val running = scanStatus?.running == true
    var showStartDialog by remember { mutableStateOf(false) }
    var showShutdownDialog by remember { mutableStateOf(false) }
    var showSshDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Salle de contrôle")
                        ConnectionDot(connection, shuttingDown)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Réglages")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (shuttingDown) {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "Le Jetson s'éteint…",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "L'app ne peut pas le rallumer — un accès physique est nécessaire " +
                                "pour le redémarrer.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            if (running) {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Scan en cours", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(scanStatus?.scan ?: "—", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Durée ${formatElapsed(scanStatus?.elapsedS ?: 0)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { viewModel.resumeActive(onOpenActive) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Visibility, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Reprendre l'aperçu")
                        }
                    }
                }
            }

            Button(
                onClick = { showStartDialog = true },
                enabled = isConnected && !running && !shuttingDown,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
            ) {
                Icon(Icons.Default.PlayArrow, null, Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Text("Nouveau scan", style = MaterialTheme.typography.titleMedium)
            }

            if (!isConnected && !shuttingDown) {
                Text(
                    "Connecte-toi au Jetson pour démarrer un scan.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.weight(1f))

            // Secondary / utility actions, visually separated from the primary "Nouveau scan".
            OutlinedButton(
                onClick = { showSshDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Terminal, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("SSH")
            }

            // Power OFF: deliberate, destructive. Disabled during a scan / when disconnected.
            OutlinedButton(
                onClick = { showShutdownDialog = true },
                enabled = isConnected && !running && !shuttingDown,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.PowerSettingsNew, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Éteindre le Jetson")
            }
        }
    }

    if (showShutdownDialog) {
        AlertDialog(
            onDismissRequest = { showShutdownDialog = false },
            icon = { Icon(Icons.Default.PowerSettingsNew, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Éteindre le Jetson ?") },
            text = {
                Text(
                    "Le Jetson va s'éteindre complètement. L'app ne peut pas le rallumer : " +
                        "un accès physique est nécessaire pour le redémarrer."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showShutdownDialog = false
                        viewModel.shutdownJetson { msg ->
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Éteindre") }
            },
            dismissButton = { TextButton(onClick = { showShutdownDialog = false }) { Text("Annuler") } }
        )
    }

    if (showSshDialog) {
        SshHelperDialog(viewModel = viewModel, onDismiss = { showSshDialog = false })
    }

    if (showStartDialog) {
        StartScanDialog(
            initialLocation = viewModel.lastLocation,
            onStart = { location ->
                showStartDialog = false
                viewModel.startScan(
                    location = location,
                    onActive = onOpenActive,
                    onError = { msg -> Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
                )
            },
            onDismiss = { showStartDialog = false }
        )
    }
}

@Composable
private fun StartScanDialog(
    initialLocation: String,
    onStart: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var location by remember { mutableStateOf(initialLocation) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nouveau scan") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text("Lieu") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Laisser vide réutilise le dernier lieu" +
                        if (initialLocation.isNotBlank()) " (« $initialLocation »)." else ".",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = { onStart(location) }) {
                Icon(Icons.Default.PlayArrow, null)
                Spacer(Modifier.width(6.dp))
                Text("Démarrer")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

// ===========================================================================
// Active Scan — live 3D preview + HUD + Stop & Save
// ===========================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveScanScreen(
    viewModel: ScanControlViewModel,
    onExit: () -> Unit,
    onGoToLibrary: () -> Unit,
    onAddNotes: (String) -> Unit
) {
    val phase by viewModel.phase
    val scanName by viewModel.scanName
    val elapsed by viewModel.elapsed
    val link by viewModel.link
    val pose by viewModel.pose
    val previewPoints by viewModel.previewPoints
    val receiving by viewModel.receiving
    val scanStatus by viewModel.scanStatus

    var glView by remember { mutableStateOf<MyGLSurfaceView?>(null) }
    var helpersOn by remember { mutableStateOf(false) }
    var orthographic by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        viewModel.startPolling()
        viewModel.attachPreview()
        onDispose {
            viewModel.detachPreview()
            viewModel.stopPolling()
        }
    }

    // Feed the current sensor pose to the renderer's marker.
    LaunchedEffect(pose) {
        pose?.let { glView?.renderer?.setPoseMarker(it[0], it[1], it[2]) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                MyGLSurfaceView(ctx, liveMode = true).also { view ->
                    view.renderer.previewSource = viewModel.previewCloud
                    glView = view
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // HUD
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    scanName ?: "Scan en cours",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text("⏱ ${formatElapsed(elapsed)}", style = MaterialTheme.typography.bodyMedium)
                Text("$previewPoints points" + if (viewModel.previewCapped) " (max)" else "",
                    style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HealthDot("odom", scanStatus?.odomOk == true)
                    HealthDot("cloud", scanStatus?.cloudOk == true)
                }
                Text(
                    when {
                        link == PreviewLinkState.Reconnecting -> "Reconnexion…"
                        link == PreviewLinkState.Connecting -> "Connexion…"
                        receiving -> "Réception des données"
                        else -> "Pas de données — bougez le capteur"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (receiving) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
            }
        }

        // Viewer options (frame-all, ruler, projection) — same cluster as the saved-PCD viewer.
        ViewerOptionsCluster(
            helpersOn = helpersOn,
            orthographic = orthographic,
            onFrameAll = { glView?.frameAll() },
            onToggleHelpers = { helpersOn = !helpersOn; glView?.setHelpersAlways(helpersOn) },
            onToggleProjection = { orthographic = !orthographic; glView?.setOrthographic(orthographic) },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(24.dp)
        )

        // Stop & Save
        Button(
            onClick = { viewModel.stopScan() },
            enabled = phase == ControlPhase.Active,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(24.dp)
                .height(56.dp)
        ) {
            if (phase == ControlPhase.Stopping) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onError
                )
                Spacer(Modifier.width(10.dp))
                Text("Enregistrement…")
            } else {
                Icon(Icons.Default.Stop, null)
                Spacer(Modifier.width(8.dp))
                Text("Arrêter & enregistrer")
            }
        }
    }

    // Post-scan summary
    if (phase == ControlPhase.Summary) {
        val result = viewModel.stopResult.value
        AlertDialog(
            onDismissRequest = { /* force a choice */ },
            title = { Text("Scan terminé") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(result?.scan ?: scanName ?: "—", style = MaterialTheme.typography.titleSmall)
                    Text("Nuage de points : ${result?.pcdSize ?: "—"}")
                    Text("Bag : ${result?.bagSize ?: "—"}")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = result?.scan ?: scanName
                    viewModel.finishSummary()
                    if (name != null) onAddNotes(name) else onGoToLibrary()
                }) { Text("Ajouter des notes") }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.finishSummary()
                    onGoToLibrary()
                }) { Text("Voir la bibliothèque") }
            }
        )
    }
}

@Composable
private fun HealthDot(label: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .background(
                    if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    CircleShape
                )
        )
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ConnectionDot(connection: ConnectionState, shuttingDown: Boolean = false) {
    val (color, label) = when {
        shuttingDown -> MaterialTheme.colorScheme.onSurfaceVariant to "Arrêt en cours…"
        connection is ConnectionState.Connected -> MaterialTheme.colorScheme.primary to "Connecté"
        connection is ConnectionState.Connecting -> MaterialTheme.colorScheme.tertiary to "Connexion…"
        else -> MaterialTheme.colorScheme.error to "Hors-ligne"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ===========================================================================
// SSH handoff helper — prefilled details + clipboard + intent launch (no terminal)
// ===========================================================================

@Composable
private fun SshHelperDialog(
    viewModel: ScanControlViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val host = viewModel.jetsonHost
    var user by remember { mutableStateOf(viewModel.sshUser) }
    val command = "ssh ${user.trim().ifBlank { "user" }}@$host"

    AlertDialog(
        onDismissRequest = {
            viewModel.sshUser = user           // persist whatever was typed
            onDismiss()
        },
        icon = { Icon(Icons.Default.Terminal, null) },
        title = { Text("Connexion SSH") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text("Utilisateur") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Hôte : $host", style = MaterialTheme.typography.bodyMedium)
                    Text("Port : 22", style = MaterialTheme.typography.bodyMedium)
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        command,
                        modifier = Modifier.padding(10.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Text(
                    "Ouvre la connexion dans une app SSH installée (ex. Termius).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                viewModel.sshUser = user
                val uri = Uri.parse("ssh://${user.trim().ifBlank { "user" }}@$host")
                val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(intent)
                    onDismiss()
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(
                        context,
                        "Aucune app SSH trouvée. Installe un client SSH (ex. Termius).",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Ouvrir dans une app SSH")
            }
        },
        dismissButton = {
            TextButton(onClick = {
                viewModel.sshUser = user
                clipboard.setText(AnnotatedString(command))
                Toast.makeText(context, "Commande copiée", Toast.LENGTH_SHORT).show()
            }) {
                Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Copier")
            }
        }
    )
}
