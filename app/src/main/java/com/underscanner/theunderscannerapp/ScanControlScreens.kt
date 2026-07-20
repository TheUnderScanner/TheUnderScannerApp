package com.underscanner.theunderscannerapp

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.input.pointer.pointerInput
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
    val health by viewModel.health
    val phase by viewModel.phase

    DisposableEffect(Unit) {
        viewModel.startPolling()
        onDispose { viewModel.stopPolling() }
    }

    val isConnected = connection is ConnectionState.Connected
    val running = scanStatus?.running == true
    val starting = phase == ControlPhase.Starting
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

            if (starting) {
                StartingScanCard()
            }

            Button(
                onClick = { showStartDialog = true },
                enabled = isConnected && !running && !shuttingDown && !starting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
            ) {
                if (starting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = LocalContentColor.current
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("Démarrage…", style = MaterialTheme.typography.titleMedium)
                } else {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(28.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Nouveau scan", style = MaterialTheme.typography.titleMedium)
                }
            }

            if (!isConnected && !shuttingDown) {
                Text(
                    "Connecte-toi au Jetson pour démarrer un scan.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!shuttingDown) {
                JetsonHealthCard(health = health, stale = !isConnected)
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

/**
 * Feedback while `POST /scan/start` is in flight.
 *
 * The backend blocks for at least 5 s on purpose — it sleeps 3 s after spawning the Livox
 * driver and 2 s after SLAM so each has time to come up before the bag recorder starts.
 * Without this the user taps and watches an idle screen, so the stage text is timed to
 * that real sequence rather than being a decorative fake progress bar.
 */
@Composable
private fun StartingScanCard() {
    var elapsed by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            elapsed++
        }
    }
    val stage = when {
        elapsed < 3 -> "Démarrage du LiDAR…"
        elapsed < 5 -> "Initialisation du SLAM…"
        elapsed < 12 -> "Lancement de l'enregistrement…"
        else -> "Toujours en cours — le Jetson met plus de temps que prévu."
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Démarrage du scan", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text(
                    stage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
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
    val health by viewModel.health

    var glView by remember { mutableStateOf<MyGLSurfaceView?>(null) }
    var helpersOn by remember { mutableStateOf(false) }
    var orthographic by remember { mutableStateOf(false) }
    // Coloring state — session-only for the live view (not persisted).
    var colorMode by remember { mutableStateOf(ColorMode.UNIFORM) }
    var reflLow by remember { mutableStateOf(0f) }
    var reflHigh by remember { mutableStateOf(1f) }
    var noiseFilter by remember { mutableStateOf(NoiseFilter.OFF) }
    var showTrajectory by remember { mutableStateOf(true) } // path builds as the scan runs
    // Camera control mode: false = one-finger drag orbits, true = one-finger drag pans the pivot.
    var panMode by remember { mutableStateOf(false) }

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
                    view.renderer.trajectorySource = viewModel.trajectory
                    view.setColorMode(colorMode)
                    view.setReflBounds(reflLow, reflHigh)
                    view.setNoiseFilter(noiseFilter)
                    view.setShowTrajectory(showTrajectory)
                    view.setPanMode(panMode)
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
                health?.let { HudHealthLine(it) }
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

        // Coloring selector + viewer options — same clusters as the saved-PCD viewer.
        // (Distance mode uses the live sensor pose already fed to the renderer's marker.)
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp)
        ) {
            ColorModeCluster(
                mode = colorMode,
                reflLow = reflLow,
                reflHigh = reflHigh,
                onModeChange = { colorMode = it; glView?.setColorMode(it) },
                onReflBoundsChange = { lo, hi ->
                    reflLow = lo; reflHigh = hi; glView?.setReflBounds(lo, hi)
                }
            )
            ViewerOptionsCluster(
                helpersOn = helpersOn,
                orthographic = orthographic,
                onFrameAll = { glView?.frameAll() },
                onToggleHelpers = { helpersOn = !helpersOn; glView?.setHelpersAlways(helpersOn) },
                onToggleProjection = { orthographic = !orthographic; glView?.setOrthographic(orthographic) },
                noiseFilter = noiseFilter,
                onNoiseFilterChange = { noiseFilter = it; glView?.setNoiseFilter(it) },
                showTrajectory = showTrajectory,
                onToggleTrajectory = {
                    showTrajectory = !showTrajectory
                    glView?.setShowTrajectory(showTrajectory)
                }
            )
        }

        // Camera control-mode toggle (orbit ⇄ pan the pivot), bottom-left — same as the PCD viewer.
        FloatingActionButton(
            onClick = { panMode = !panMode; glView?.setPanMode(panMode) },
            containerColor = if (panMode) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.secondaryContainer,
            contentColor = if (panMode) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(24.dp)
        ) {
            Icon(
                if (panMode) Icons.Default.OpenWith else Icons.Default.Autorenew,
                contentDescription = if (panMode) "Mode déplacement (pan)" else "Mode orbite"
            )
        }

        // Stop & Save — top-right, clear of the status bar.
        Button(
            onClick = { viewModel.stopScan() },
            enabled = phase == ControlPhase.Active,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(12.dp)
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

    // Saving can take the better part of a minute — block the screen and say what's happening.
    if (phase == ControlPhase.Stopping) {
        StoppingScanOverlay()
    }
    // Leaving mid-save would strand the user on the library with no summary, while the
    // Jetson is still finalising. The stop itself runs in viewModelScope and would survive,
    // but there is nothing useful to do elsewhere, so swallow Back until it resolves.
    BackHandler(enabled = phase == ControlPhase.Stopping) { /* intentionally blocked */ }

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

// ===========================================================================
// Jetson telemetry readouts (GET /system/health)
//
// Every metric is optional — the backend omits keys the board doesn't expose — so
// each row renders only when its value is present rather than showing a fake 0.
// ===========================================================================

/** No "warning" role exists in the M3 scheme, so the mid-threshold amber is explicit. */
private val WarnAmber = Color(0xFFFF9800)

/** Orin throttles around 85-90 degC junction; amber is the "watch it" band below that. */
@Composable
private fun tempColor(c: Float): Color = when {
    c >= 85f -> MaterialTheme.colorScheme.error
    c >= 70f -> WarnAmber
    else -> MaterialTheme.colorScheme.onSurface
}

@Composable
private fun fractionColor(f: Float): Color = when {
    f >= 0.90f -> MaterialTheme.colorScheme.error
    f >= 0.75f -> WarnAmber
    else -> MaterialTheme.colorScheme.onSurface
}

private fun f1(v: Float): String = "%.1f".format(v)

@Composable
private fun MetricRow(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = if (valueColor == Color.Unspecified) MaterialTheme.colorScheme.onSurface else valueColor
        )
    }
}

/** Version-proof mini bar (avoids the M3 progress-indicator API churn). */
@Composable
private fun MiniBar(fraction: Float, color: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(2.dp))
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(color, RoundedCornerShape(2.dp))
        )
    }
}

/** Full telemetry card for the Control Room, where there is room for detail. */
@Composable
private fun JetsonHealthCard(health: SystemHealth?, stale: Boolean) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("État du Jetson", style = MaterialTheme.typography.titleMedium)
                health?.static?.powerMode?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (health == null || health.values.isEmpty()) {
                Text(
                    "Télémétrie indisponible.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }

            if (stale) {
                Text(
                    "Valeurs figées — Jetson injoignable.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            health.cpuPercent?.let { cpu ->
                val freq = health.cpuMhz?.let { " · ${it.toInt()} MHz" }.orEmpty()
                MetricRow("CPU", "${f1(cpu)} %$freq", fractionColor(cpu / 100f))
                MiniBar(cpu / 100f, fractionColor(cpu / 100f))
            }
            health.gpuPercent?.let { MetricRow("GPU", "${f1(it)} %") }

            health.memUsedMb?.let { used ->
                val total = health.static.memTotalMb
                val label = if (total != null) "${f1(used / 1024f)} / ${f1(total / 1024f)} Go"
                            else "${f1(used / 1024f)} Go"
                val frac = health.memFraction
                MetricRow("RAM", label, frac?.let { fractionColor(it) } ?: Color.Unspecified)
                frac?.let { MiniBar(it, fractionColor(it)) }
            }

            // Junction temperature is the one that governs throttling, so it leads.
            health.tempTj?.let { MetricRow("Temp. jonction", "${f1(it)} °C", tempColor(it)) }
            listOfNotNull(
                health.tempCpu?.let { "CPU ${f1(it)}" },
                health.tempGpu?.let { "GPU ${f1(it)}" },
                health.tempSoc?.let { "SoC ${f1(it)}" }
            ).takeIf { it.isNotEmpty() }?.let {
                MetricRow("Détail", it.joinToString(" · ") + " °C")
            }

            health.wattsIn?.let { w ->
                val detail = listOfNotNull(
                    health["w_cpu_gpu"]?.let { "CPU/GPU ${f1(it)}" },
                    health["w_soc"]?.let { "SoC ${f1(it)}" }
                ).joinToString(" · ")
                MetricRow("Consommation", "${f1(w)} W" + if (detail.isNotEmpty()) "  ($detail)" else "")
            }

            health.diskFreeGb?.let {
                MetricRow("Disque libre", "${f1(it)} Go",
                    if (it < 10f) MaterialTheme.colorScheme.error
                    else if (it < 25f) WarnAmber else Color.Unspecified)
            }
        }
    }
}

/**
 * One-line HUD readout for the Active Scan overlay, where space is tight: the three
 * numbers that answer "should I worry right now" — throttle, memory, battery.
 */
@Composable
private fun HudHealthLine(health: SystemHealth) {
    val parts = listOfNotNull(
        health.tempTj?.let { "${f1(it)} °C" },
        health.memUsedMb?.let { "${f1(it / 1024f)} Go" },
        health.wattsIn?.let { "${f1(it)} W" }
    )
    if (parts.isEmpty()) return
    Text(
        parts.joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = health.tempTj?.let { tempColor(it) } ?: MaterialTheme.colorScheme.onSurface
    )
}

/**
 * Blocking feedback while `POST /scan/stop` is in flight.
 *
 * Stopping is the slowest operation in the app: the backend calls `/map_save` (up to 30 s),
 * then waits for the `.pcd` size to stabilise (up to 10 s) so SLAM can't be killed mid-write,
 * then SIGINTs the bag recorder, SLAM and the driver in turn (up to 8 s each). A scan of any
 * size routinely spends 15-30 s here. The stage text follows that real sequence, and the
 * overlay consumes touches so the still-live 3D view underneath can't be mistaken for an
 * ongoing scan.
 */
@Composable
private fun StoppingScanOverlay() {
    var elapsed by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            elapsed++
        }
    }
    val stage = when {
        elapsed < 8 -> "Enregistrement de la carte…"
        elapsed < 18 -> "Finalisation du nuage de points…"
        elapsed < 40 -> "Arrêt du LiDAR et du SLAM…"
        else -> "Toujours en cours — gros scan, ne quitte pas l'écran."
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.75f))
            // Consume every pointer event so nothing reaches the GL surface below.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        ElevatedCard(modifier = Modifier.padding(32.dp)) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(modifier = Modifier.size(40.dp), strokeWidth = 3.dp)
                Spacer(Modifier.height(16.dp))
                Text("Enregistrement du scan", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    stage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Ne coupe pas le Jetson pendant l'enregistrement.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
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
