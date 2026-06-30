package com.underscanner.theunderscannerapp

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Collapsible cluster of point-cloud viewer options. A single icon-only access button toggles
 * a vertical panel of child actions; pressing any child runs it and auto-collapses the panel.
 * Toggle children (ruler, projection) are tinted when active. New future options drop in here.
 *
 * Caller owns the toggle states (so they survive recomposition and can be pushed to the GL view);
 * this composable is purely presentational.
 */
@Composable
fun ViewerOptionsCluster(
    helpersOn: Boolean,
    orthographic: Boolean,
    onFrameAll: () -> Unit,
    onToggleHelpers: () -> Unit,
    onToggleProjection: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
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
