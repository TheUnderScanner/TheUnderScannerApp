package com.underscanner.theunderscannerapp

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.withTimeout
import kotlin.math.roundToInt

/**
 * Collapsible cluster of point-cloud viewer options. A single icon-only access button toggles
 * a vertical panel of child actions; pressing any child runs it and auto-collapses the panel.
 * Toggle children (ruler, projection, auto-orbit) are tinted when active. New future options
 * drop in here.
 *
 * Caller owns the toggle states (so they survive recomposition and can be pushed to the GL view);
 * this composable is purely presentational.
 *
 * The auto-orbit child is optional: it renders only when [onToggleAutoOrbit] is supplied. A tap
 * toggles the automatic "show" spin; a long-press pops a graduated speed slider above the button
 * that the still-pressed finger drags up/down to pick the rotation speed.
 */
@Composable
fun ViewerOptionsCluster(
    helpersOn: Boolean,
    orthographic: Boolean,
    onFrameAll: () -> Unit,
    onToggleHelpers: () -> Unit,
    onToggleProjection: () -> Unit,
    autoOrbitOn: Boolean = false,
    autoOrbitSpeed: Float = 0f,
    onToggleAutoOrbit: (() -> Unit)? = null,
    onAutoOrbitSpeedChange: ((Float) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Auto-orbit is the topmost sub-button.
                if (onToggleAutoOrbit != null) {
                    AutoOrbitFab(
                        active = autoOrbitOn,
                        speed = autoOrbitSpeed,
                        onToggle = onToggleAutoOrbit,
                        onSpeedChange = { onAutoOrbitSpeedChange?.invoke(it) }
                    )
                }
                OptionFab(Icons.Default.CenterFocusStrong, "Tout afficher") {
                    onFrameAll(); expanded = false
                }
                OptionFab(Icons.Default.Straighten, "Repères", active = helpersOn) {
                    onToggleHelpers(); expanded = false
                }
                OptionFab(
                    if (orthographic) Icons.Default.GridOn else Icons.Default.CameraAlt,
                    "Projection", active = orthographic
                ) {
                    onToggleProjection(); expanded = false
                }
            }
        }

        FloatingActionButton(onClick = { expanded = !expanded }) {
            Icon(
                if (expanded) Icons.Default.Close else Icons.Default.Tune,
                contentDescription = "Options de vue"
            )
        }
    }
}

@Composable
private fun OptionFab(
    icon: ImageVector,
    description: String,
    active: Boolean = false,
    onClick: () -> Unit
) {
    SmallFloatingActionButton(
        onClick = onClick,
        containerColor = if (active) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (active) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSecondaryContainer
    ) {
        Icon(icon, contentDescription = description)
    }
}

private val AUTO_ORBIT_FAB_SIZE = 40.dp
private val AUTO_ORBIT_SLIDER_HEIGHT = 180.dp
private val AUTO_ORBIT_SLIDER_WIDTH = 44.dp
private val AUTO_ORBIT_SLIDER_GAP = 10.dp

/**
 * Auto-orbit toggle FAB with a long-press-to-adjust speed slider.
 *
 * A single manual pointer gesture drives both interactions: a quick tap (release before the
 * long-press timeout) flips the toggle via [onToggle]; holding past the timeout enters "adjust"
 * mode, revealing a graduated vertical slider above the button (via [Popup], so it isn't clipped
 * by the collapsing panel). While the finger stays down its vertical travel is mapped to a
 * 0..1 fraction (slide up = faster) and streamed out through [onSpeedChange]; the caller both
 * enables the orbit and sets its speed from that fraction.
 */
@Composable
private fun AutoOrbitFab(
    active: Boolean,
    speed: Float,
    onToggle: () -> Unit,
    onSpeedChange: (Float) -> Unit
) {
    val density = LocalDensity.current
    val viewConfig = LocalViewConfiguration.current
    val sliderHeightPx = with(density) { AUTO_ORBIT_SLIDER_HEIGHT.toPx() }
    val popupYOffset = with(density) {
        -(AUTO_ORBIT_SLIDER_HEIGHT + AUTO_ORBIT_SLIDER_GAP).toPx().roundToInt()
    }

    var adjusting by remember { mutableStateOf(false) }
    var liveFraction by remember { mutableStateOf(speed) }

    val containerColor = if (active || adjusting) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.secondaryContainer
    val contentColor = if (active || adjusting) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSecondaryContainer

    Box {
        if (adjusting) {
            Popup(
                alignment = Alignment.TopCenter,
                offset = IntOffset(0, popupYOffset),
                properties = PopupProperties(
                    focusable = false,
                    dismissOnBackPress = false,
                    dismissOnClickOutside = false
                )
            ) {
                SpeedSliderTrack(fraction = liveFraction)
            }
        }

        Surface(
            color = containerColor,
            contentColor = contentColor,
            shape = MaterialTheme.shapes.medium,
            shadowElevation = 6.dp,
            modifier = Modifier
                .size(AUTO_ORBIT_FAB_SIZE)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val startY = down.position.y

                        // Distinguish tap from long-press: if the finger lifts before the
                        // long-press timeout it's a tap; holding past the timeout enters adjust
                        // mode. A cancel before the timeout returns null WITHOUT timing out, so a
                        // dedicated flag tells a real timeout apart from that.
                        var longPress = false
                        val up = try {
                            withTimeout(viewConfig.longPressTimeoutMillis) {
                                waitForUpOrCancellation()
                            }
                        } catch (_: PointerEventTimeoutCancellationException) {
                            longPress = true
                            null
                        }

                        if (!longPress) {
                            if (up != null) onToggle() // clean lift → toggle; cancel → do nothing
                            return@awaitEachGesture
                        }

                        // Long-press: reveal the slider and start streaming speed from the
                        // bottom (min). The still-pressed finger's vertical drag sets the rate.
                        adjusting = true
                        liveFraction = 0f
                        onSpeedChange(0f)

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || !change.pressed) break
                            val dy = startY - change.position.y // up = positive
                            liveFraction = (dy / sliderHeightPx).coerceIn(0f, 1f)
                            onSpeedChange(liveFraction)
                            change.consume()
                        }
                        adjusting = false
                    }
                }
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.RotateRight, contentDescription = "Orbite automatique")
            }
        }
    }
}

/**
 * Vertical graduated slider drawn while adjusting the auto-orbit speed. Filled from the bottom
 * up to [fraction] (0..1), with tick marks (long every 5th) up the right side and a thumb at the
 * current level. Purely visual — the gesture lives in [AutoOrbitFab].
 */
@Composable
private fun SpeedSliderTrack(fraction: Float) {
    val trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
    val fillColor = MaterialTheme.colorScheme.primary
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    val thumbColor = MaterialTheme.colorScheme.primary
    val thumbRing = MaterialTheme.colorScheme.onPrimary

    Box(
        modifier = Modifier
            .width(AUTO_ORBIT_SLIDER_WIDTH)
            .height(AUTO_ORBIT_SLIDER_HEIGHT)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val trackW = 8.dp.toPx()
            val cx = w * 0.38f
            val left = cx - trackW / 2f
            val radius = CornerRadius(trackW / 2f, trackW / 2f)

            // Background track.
            drawRoundRect(
                color = trackColor,
                topLeft = Offset(left, 0f),
                size = Size(trackW, h),
                cornerRadius = radius
            )

            // Graduations up the right edge of the track.
            val n = 10
            val tickX = cx + trackW / 2f + 4.dp.toPx()
            for (i in 0..n) {
                val y = h - (i.toFloat() / n) * h
                val len = if (i % 5 == 0) 12.dp.toPx() else 7.dp.toPx()
                drawLine(
                    color = tickColor,
                    start = Offset(tickX, y),
                    end = Offset(tickX + len, y),
                    strokeWidth = 2f
                )
            }

            // Filled portion from bottom up to the thumb.
            val f = fraction.coerceIn(0f, 1f)
            val thumbY = h - f * h
            drawRoundRect(
                color = fillColor,
                topLeft = Offset(left, thumbY),
                size = Size(trackW, h - thumbY),
                cornerRadius = radius
            )

            // Thumb.
            drawCircle(color = thumbRing, radius = 11.dp.toPx(), center = Offset(cx, thumbY))
            drawCircle(color = thumbColor, radius = 8.dp.toPx(), center = Offset(cx, thumbY))
        }
    }
}
