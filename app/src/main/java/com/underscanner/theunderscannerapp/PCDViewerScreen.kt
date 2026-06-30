package com.underscanner.theunderscannerapp

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

/**
 * 3D point-cloud viewer. Wraps the kept OpenGL stack (MyGLSurfaceView / MyGLRenderer)
 * driven by the single-state orbit camera. Controls are gesture-only (see
 * [MyGLSurfaceView]); the only UI control is "Frame All" to refit the whole cloud.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PCDViewerScreen(
    fileName: String,
    onBack: () -> Unit
) {
    var glView by remember { mutableStateOf<MyGLSurfaceView?>(null) }

    var helpersOn by remember { mutableStateOf(false) }
    var orthographic by remember { mutableStateOf(false) }

    var showPointCount by remember { mutableStateOf(true) }
    var pointCount by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        delay(3000)
        showPointCount = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(fileName, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
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
                    val view = MyGLSurfaceView(ctx, fileName)
                    pointCount = view.getPointCount()
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

            ViewerOptionsCluster(
                helpersOn = helpersOn,
                orthographic = orthographic,
                onFrameAll = { glView?.frameAll() },
                onToggleHelpers = { helpersOn = !helpersOn; glView?.setHelpersAlways(helpersOn) },
                onToggleProjection = { orthographic = !orthographic; glView?.setOrthographic(orthographic) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp)
            )
        }
    }
}
