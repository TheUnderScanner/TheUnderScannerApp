package com.underscanner.theunderscannerapp

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** USB plug with a small arrow: "send to the USB stick". */
@Composable
fun UsbSendIcon(tint: Color = LocalContentColor.current, size: Dp = 24.dp) {
    Box(Modifier.size(size)) {
        Icon(Icons.Default.Usb, contentDescription = null, tint = tint,
            modifier = Modifier.size(size * 0.85f).align(Alignment.TopStart))
        Box(
            Modifier
                .size(size * 0.55f)
                .align(Alignment.BottomEnd)
                .background(MaterialTheme.colorScheme.surface, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = tint,
                modifier = Modifier.size(size * 0.45f))
        }
    }
}

private fun formatEta(s: Long): String = when {
    s < 60 -> "${s} s"
    s < 3600 -> "${s / 60} min ${s % 60} s"
    else -> "${s / 3600} h ${(s % 3600) / 60} min"
}

/** "45 % · 12.3 MiB/s · reste 1 min 20 s" */
fun UsbTransfer.progressText(): String = when (state) {
    "copying" -> listOfNotNull(
        "$percent %",
        rateBps?.let { humanSize(it.toLong()) + "/s" },
        etaS?.let { "reste ${formatEta(it)}" }
    ).joinToString(" · ")
    "syncing" -> "Écriture sur la clé…"
    "done" -> "Copié sur ${label ?: "la clé"} ✓"
    "cancelled" -> "Copie annulée"
    "error" -> "Échec : ${error ?: "erreur inconnue"}"
    else -> ""
}

// ===========================================================================
// Control Room card: sticks, mount, eject, current copy
// ===========================================================================

@Composable
fun UsbCard(usb: UsbViewModel, connected: Boolean, scanRunning: Boolean) {
    if (!usb.supported) return
    val context = LocalContext.current
    val t = usb.transfer

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Usb, null)
                Spacer(Modifier.width(8.dp))
                Text("Clé USB", style = MaterialTheme.typography.titleMedium)
            }
            if (usb.devices.isEmpty()) {
                Text(
                    if (connected) "Aucune clé détectée : branche-la sur le Jetson."
                    else "Connecte-toi au Jetson pour voir les clés.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            usb.devices.forEach { d ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(d.name, style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(d.details, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (d.mounted) {
                            Text("Montée · ${d.freeHuman ?: "?"} libres",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
                        if (d.isFat32) {
                            Text("FAT32 : 4 Go max par fichier",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    val busy = usb.busyDevice == d.device
                    if (d.mounted) {
                        OutlinedButton(
                            onClick = {
                                usb.eject(d) { err ->
                                    Toast.makeText(context,
                                        err ?: "Tu peux retirer la clé ${d.name}",
                                        Toast.LENGTH_LONG).show()
                                }
                            },
                            enabled = connected && !busy && !t.active
                        ) {
                            if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Default.Eject, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Éjecter")
                        }
                    } else {
                        Button(
                            onClick = {
                                usb.mount(d) { err ->
                                    Toast.makeText(context, err ?: "${d.name} montée",
                                        Toast.LENGTH_SHORT).show()
                                }
                            },
                            enabled = connected && !busy
                        ) {
                            if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp,
                                color = LocalContentColor.current)
                            else Icon(Icons.Default.Usb, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Monter")
                        }
                    }
                }
            }
            if (t.active || (t.state == "error" && usb.visibleTransfer != null)) {
                HorizontalDivider()
                Text("Copie de ${t.scan ?: "—"}", style = MaterialTheme.typography.bodySmall)
                UsbTransferBar(t, onCancel = { usb.cancelCopy() })
            }
            if (scanRunning && usb.mounted != null) {
                Text("Copie impossible pendant un scan.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (usb.mounted != null && !t.active) {
                Text("Copie un scan depuis la bibliothèque (bouton USB de chaque scan).",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ===========================================================================
// Progress bar (Library card + Control Room)
// ===========================================================================

@Composable
fun UsbTransferBar(t: UsbTransfer, onCancel: () -> Unit) {
    Column {
        when (t.state) {
            "copying" -> LinearProgressIndicator(
                progress = { t.percent / 100f }, modifier = Modifier.fillMaxWidth())
            "syncing" -> LinearProgressIndicator(Modifier.fillMaxWidth())
            "done" -> LinearProgressIndicator(progress = { 1f }, modifier = Modifier.fillMaxWidth())
            else -> {}
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Usb, null, Modifier.size(14.dp),
                tint = if (t.state == "error") MaterialTheme.colorScheme.error
                       else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
            Text(
                t.progressText(),
                style = MaterialTheme.typography.bodySmall,
                color = if (t.state == "error") MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (t.state == "copying") TextButton(onClick = onCancel) { Text("Annuler") }
        }
    }
}

// ===========================================================================
// Copy confirmation (Library): size, free space, and why it can't work
// ===========================================================================

@Composable
fun UsbCopyDialog(scan: String, usb: UsbViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var plan by remember { mutableStateOf<UsbPlan?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(scan) {
        usb.plan(scan).fold(onSuccess = { plan = it }, onFailure = { error = it.message ?: "Erreur" })
    }
    val p = plan
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { UsbSendIcon() },
        title = { Text("Copier sur la clé USB") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(scan, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                when {
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                    p == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    else -> {
                        p.device?.let { d ->
                            Text("Vers ${d.name} (${d.fsLabel}) · ${d.freeHuman ?: "?"} libres",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            "Scan complet : ${p.totalHuman}" +
                                if (p.already) " — déjà sur la clé (rien à recopier)"
                                else if (p.neededHuman != p.totalHuman) " · à copier : ${p.neededHuman}"
                                else "",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text("Bag, nuage, config, notes, trajectoire et log de santé, " +
                            "rangés dans underscanner/ sur la clé. Le scan reste sur le Jetson.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        p.problems.forEach {
                            Text("✖ $it", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onDismiss()
                    usb.startCopy(scan) { err ->
                        if (err != null) Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                    }
                },
                enabled = p != null && p.ok
            ) { Text(if (p?.already == true) "Vérifier / recopier" else "Copier") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}
