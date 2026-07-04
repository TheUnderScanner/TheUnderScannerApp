package com.underscanner.theunderscannerapp

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

/**
 * 3D point-cloud viewer. Wraps the kept OpenGL stack (MyGLSurfaceView / MyGLRenderer)
 * driven by the single-state orbit camera. Fully gesture-driven with no top bar — the GL
 * surface fills the whole screen and Android's back gesture handles leaving. The only UI
 * controls are the viewer options cluster (Frame All / helper lines / projection); the
 * helper-line and projection toggles persist across sessions via [SettingsRepository].
 */
@Composable
fun PCDViewerScreen(
    fileName: String
) {
    val context = LocalContext.current
    val settings = remember { SettingsRepository(context) }

    var glView by remember { mutableStateOf<MyGLSurfaceView?>(null) }

    var helpersOn by remember { mutableStateOf(settings.viewerHelpers) }
    var orthographic by remember { mutableStateOf(settings.viewerOrthographic) }
    // Camera control mode: false = one-finger drag orbits, true = one-finger drag pans the pivot.
    var panMode by remember { mutableStateOf(false) }

    var showPointCount by remember { mutableStateOf(true) }
    var pointCount by remember { mutableStateOf(0) }

    // Graduation scale: flashed top-left each time it changes (1 m → 10 m → 100 m → 1 km …).
    var scaleLabel by remember { mutableStateOf<String?>(null) }
    var scaleVersion by remember { mutableStateOf(0) }
    var showScale by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(3000)
        showPointCount = false
    }

    // Restart on every scale change so rapid zooming keeps it visible, then fades out.
    LaunchedEffect(scaleVersion) {
        if (scaleVersion == 0) return@LaunchedEffect
        showScale = true
        delay(1500)
        showScale = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val view = MyGLSurfaceView(ctx, fileName)
                pointCount = view.getPointCount()
                view.setOnScaleChanged { step ->
                    scaleLabel = formatScale(step)
                    scaleVersion++
                }
                // Apply the persisted viewer toggles to the fresh GL view.
                view.setHelpersAlways(helpersOn)
                view.setOrthographic(orthographic)
                view.setPanMode(panMode)
                glView = view
                view
            },
            modifier = Modifier.fillMaxSize()
        )

        AnimatedVisibility(
            visible = showPointCount,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(16.dp)
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

        AnimatedVisibility(
            visible = showScale,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 16.dp, top = 48.dp)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    scaleLabel ?: "",
                    modifier = Modifier.padding(8.dp),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }

        // Camera control-mode toggle (orbit ⇄ pan the pivot), sub-button sized, bottom-left.
        SmallFloatingActionButton(
            onClick = {
                panMode = !panMode
                glView?.setPanMode(panMode)
            },
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

        ViewerOptionsCluster(
            helpersOn = helpersOn,
            orthographic = orthographic,
            onFrameAll = { glView?.frameAll() },
            onToggleHelpers = {
                helpersOn = !helpersOn
                settings.viewerHelpers = helpersOn
                glView?.setHelpersAlways(helpersOn)
            },
            onToggleProjection = {
                orthographic = !orthographic
                settings.viewerOrthographic = orthographic
                glView?.setOrthographic(orthographic)
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp)
        )
    }
}

/** Format a ruler step (meters) as a compact grid-scale label: "Échelle : 10 m" / "… 1 km". */
private fun formatScale(step: Float): String {
    val label = if (step >= 1000f) "${(step / 1000f).toInt()} km" else "${step.toInt()} m"
    return "Échelle : $label"
}
