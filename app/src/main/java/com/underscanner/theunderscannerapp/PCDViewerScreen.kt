package com.underscanner.theunderscannerapp

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

/**
 * 3D point-cloud viewer. Wraps the kept OpenGL stack (MyGLSurfaceView /
 * MyGLRenderer / CameraController / VirtualJoystick) and hosts the camera menu
 * (style, control mode, auto-level) in its own top bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PCDViewerScreen(
    fileName: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

    var showMenu by remember { mutableStateOf(false) }
    var showCameraDialog by remember { mutableStateOf(false) }

    var cameraMode by remember { mutableStateOf(CameraMode.ORBIT) }
    var autoLevel by remember { mutableStateOf(prefs.getBoolean("auto_level", false)) }
    var glViewRef by remember { mutableStateOf<MyGLSurfaceView?>(null) }
    var cameraController by remember { mutableStateOf<CameraController?>(null) }
    // Track the control mode reactively so overlays recompose when it changes.
    var controlMode by remember { mutableStateOf(ControlMode.TOUCH) }

    var showPointCount by remember { mutableStateOf(true) }
    var pointCount by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        delay(3000)
        showPointCount = false
    }

    // Continuous joystick input loop (~60 FPS)
    LaunchedEffect(cameraController) {
        val controller = cameraController ?: return@LaunchedEffect
        while (true) {
            controller.applyJoystickInput()
            delay(16)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(fileName, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    IconButton(onClick = { showMenu = !showMenu }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Style Caméra") },
                            leadingIcon = { Icon(Icons.Default.Videocam, null) },
                            onClick = { showCameraDialog = true; showMenu = false }
                        )
                        DropdownMenuItem(
                            text = { Text("Mode de contrôle : ${controlMode.name}") },
                            leadingIcon = { Icon(Icons.Default.TouchApp, null) },
                            onClick = {
                                cameraController?.let { c ->
                                    c.controlMode = when (c.controlMode) {
                                        ControlMode.TOUCH -> ControlMode.JOYSTICK
                                        ControlMode.JOYSTICK -> ControlMode.SPLIT
                                        ControlMode.SPLIT -> ControlMode.TOUCH
                                    }
                                    controlMode = c.controlMode
                                }
                                showMenu = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Auto-Level : ${if (autoLevel) "ON" else "OFF"}") },
                            leadingIcon = { Icon(Icons.Default.Straighten, null) },
                            onClick = {
                                autoLevel = !autoLevel
                                prefs.edit().putBoolean("auto_level", autoLevel).apply()
                                cameraController?.autoLevel = autoLevel
                                showMenu = false
                                Toast.makeText(
                                    context,
                                    "Auto-Level ${if (autoLevel) "activé" else "désactivé"}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AndroidView(
                factory = { ctx ->
                    val glView = MyGLSurfaceView(ctx, fileName)
                    pointCount = glView.getPointCount()
                    val controller = CameraController(glView)
                    controller.autoLevel = autoLevel
                    cameraController = controller
                    glViewRef = glView
                    cameraMode = glView.getCameraMode()
                    controlMode = controller.controlMode
                    glView
                },
                modifier = Modifier.fillMaxSize()
            )

            AnimatedVisibility(
                visible = showPointCount,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .padding(16.dp)
                    .align(Alignment.TopStart)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        "$pointCount points",
                        modifier = Modifier.padding(8.dp),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            // Virtual joysticks: only in FPV + JOYSTICK control mode
            if (cameraMode == CameraMode.FPV && controlMode == ControlMode.JOYSTICK) {
                AnimatedVisibility(
                    visible = true, enter = fadeIn(), exit = fadeOut(),
                    modifier = Modifier.align(Alignment.BottomStart).padding(32.dp)
                ) {
                    VirtualJoystick(onMove = { x, y -> cameraController?.updateMovementJoystick(x, y) })
                }
                AnimatedVisibility(
                    visible = true, enter = fadeIn(), exit = fadeOut(),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(32.dp)
                ) {
                    VirtualJoystick(onMove = { x, y -> cameraController?.updateLookJoystick(x, y) })
                }
            }

            // Split-control visual indicator
            if (controlMode == ControlMode.SPLIT) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawLine(
                        color = Color.White.copy(alpha = 0.3f),
                        start = Offset(size.width / 2f, 0f),
                        end = Offset(size.width / 2f, size.height),
                        strokeWidth = 2f
                    )
                }
            }
        }
    }

    if (showCameraDialog) {
        CameraStyleDialog(
            currentMode = cameraMode,
            onModeSelected = { mode ->
                cameraMode = mode
                glViewRef?.setCameraMode(mode)
                showCameraDialog = false
            },
            onDismiss = { showCameraDialog = false }
        )
    }
}

@Composable
private fun CameraStyleDialog(
    currentMode: CameraMode,
    onModeSelected: (CameraMode) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Style de Caméra", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CameraStyleOption(
                    title = "Caméra Orbite",
                    description = "Rotation autour du nuage de points",
                    icon = Icons.Default.CameraAlt,
                    isSelected = currentMode == CameraMode.ORBIT,
                    onClick = { onModeSelected(CameraMode.ORBIT) }
                )
                CameraStyleOption(
                    title = "Caméra FPV",
                    description = "Vue à la première personne",
                    icon = Icons.Default.Videocam,
                    isSelected = currentMode == CameraMode.FPV,
                    onClick = { onModeSelected(CameraMode.FPV) }
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fermer") } }
    )
}

@Composable
private fun CameraStyleOption(
    title: String,
    description: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().then(
            if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
            else Modifier
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title, style = MaterialTheme.typography.titleMedium,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    description, style = MaterialTheme.typography.bodySmall,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            if (isSelected) {
                Icon(Icons.Default.CheckCircle, "Sélectionné", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            }
        }
    }
}
