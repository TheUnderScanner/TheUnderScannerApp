package com.underscanner.theunderscannerapp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.sqrt

/**
 * Virtual joystick composable for camera controls
 * @param modifier Modifier for positioning
 * @param onMove Callback with normalized X,Y values in range [-1, 1]
 */
@Composable
fun VirtualJoystick(
    modifier: Modifier = Modifier,
    onMove: (x: Float, y: Float) -> Unit = { _, _ -> }
) {
    var thumbOffset by remember { mutableStateOf(Offset.Zero) }
    val joystickRadius = 60.dp
    val thumbRadius = 25.dp

    Box(
        modifier = modifier
            .size(joystickRadius * 2)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        // Calculate initial position relative to center
                        val centerX = size.width / 2f
                        val centerY = size.height / 2f
                        val dx = offset.x - centerX
                        val dy = offset.y - centerY

                        // Clamp to circle boundary
                        val distance = sqrt(dx * dx + dy * dy)
                        val maxDistance = centerX - thumbRadius.toPx()

                        if (distance <= maxDistance) {
                            thumbOffset = Offset(dx, dy)
                        } else {
                            val angle = kotlin.math.atan2(dy, dx)
                            thumbOffset = Offset(
                                maxDistance * kotlin.math.cos(angle),
                                maxDistance * kotlin.math.sin(angle)
                            )
                        }

                        // Normalize and send callback
                        val normalizedX = thumbOffset.x / maxDistance
                        val normalizedY = thumbOffset.y / maxDistance
                        onMove(normalizedX, normalizedY)
                    },
                    onDrag = { change, _ ->
                        change.consume()

                        val centerX = size.width / 2f
                        val centerY = size.height / 2f
                        val dx = change.position.x - centerX
                        val dy = change.position.y - centerY

                        // Clamp to circle boundary
                        val distance = sqrt(dx * dx + dy * dy)
                        val maxDistance = centerX - thumbRadius.toPx()

                        if (distance <= maxDistance) {
                            thumbOffset = Offset(dx, dy)
                        } else {
                            val angle = kotlin.math.atan2(dy, dx)
                            thumbOffset = Offset(
                                maxDistance * kotlin.math.cos(angle),
                                maxDistance * kotlin.math.sin(angle)
                            )
                        }

                        // Normalize and send callback
                        val normalizedX = thumbOffset.x / maxDistance
                        val normalizedY = thumbOffset.y / maxDistance
                        onMove(normalizedX, normalizedY)
                    },
                    onDragEnd = {
                        // Return to center
                        thumbOffset = Offset.Zero
                        onMove(0f, 0f)
                    },
                    onDragCancel = {
                        // Return to center
                        thumbOffset = Offset.Zero
                        onMove(0f, 0f)
                    }
                )
            }
    ) {
        Canvas(modifier = Modifier.size(joystickRadius * 2)) {
            drawJoystick(
                joystickRadius = joystickRadius.toPx(),
                thumbRadius = thumbRadius.toPx(),
                thumbOffset = thumbOffset
            )
        }
    }
}

private fun DrawScope.drawJoystick(
    joystickRadius: Float,
    thumbRadius: Float,
    thumbOffset: Offset
) {
    val center = Offset(size.width / 2f, size.height / 2f)

    // Draw outer circle (base)
    drawCircle(
        color = Color.White.copy(alpha = 0.3f),
        radius = joystickRadius,
        center = center
    )

    // Draw outer circle border
    drawCircle(
        color = Color.White.copy(alpha = 0.5f),
        radius = joystickRadius,
        center = center,
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f)
    )

    // Draw center crosshair for reference
    val crosshairLength = 15f
    drawLine(
        color = Color.White.copy(alpha = 0.4f),
        start = Offset(center.x - crosshairLength, center.y),
        end = Offset(center.x + crosshairLength, center.y),
        strokeWidth = 2f
    )
    drawLine(
        color = Color.White.copy(alpha = 0.4f),
        start = Offset(center.x, center.y - crosshairLength),
        end = Offset(center.x, center.y + crosshairLength),
        strokeWidth = 2f
    )

    // Draw thumb (stick)
    val thumbCenter = center + thumbOffset
    drawCircle(
        color = Color.White.copy(alpha = 0.7f),
        radius = thumbRadius,
        center = thumbCenter
    )

    // Draw thumb border
    drawCircle(
        color = Color.White.copy(alpha = 0.9f),
        radius = thumbRadius,
        center = thumbCenter,
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f)
    )
}
