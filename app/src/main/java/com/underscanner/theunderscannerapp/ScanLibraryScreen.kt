package com.underscanner.theunderscannerapp

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.launch
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanLibraryScreen(
    viewModel: ScanLibraryViewModel,
    onOpenPcd: (scanName: String) -> Unit,
    onOpenSettings: () -> Unit,
    openNotesFor: String? = null
) {
    val context = LocalContext.current
    val connection by viewModel.connection
    val scans by viewModel.scans
    val isRefreshing by viewModel.isRefreshing
    val listError by viewModel.listError
    val query by viewModel.query
    val sort by viewModel.sort

    var searching by remember { mutableStateOf(false) }
    var sortMenuOpen by remember { mutableStateOf(false) }

    // Poll /status only while this screen is foregrounded.
    DisposableEffect(Unit) {
        viewModel.startStatusPolling()
        onDispose { viewModel.stopStatusPolling() }
    }

    // Refresh on every entry so a just-finished scan shows up without manual pull-to-refresh.
    LaunchedEffect(Unit) { viewModel.refresh() }

    val isConnected = connection is ConnectionState.Connected

    // Dialog state
    var notesScan by remember { mutableStateOf<ScanInfo?>(null) }
    var configScan by remember { mutableStateOf<ScanInfo?>(null) }

    // When arriving from a just-finished scan, refresh then open its notes form once.
    var notesConsumed by remember { mutableStateOf(false) }
    LaunchedEffect(openNotesFor) {
        if (!openNotesFor.isNullOrBlank()) viewModel.refresh()
    }
    LaunchedEffect(scans, openNotesFor) {
        if (!openNotesFor.isNullOrBlank() && !notesConsumed) {
            scans.firstOrNull { it.name == openNotesFor }?.let {
                notesScan = it
                notesConsumed = true
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { viewModel.setQuery(it) },
                            placeholder = { Text("Rechercher (nom, lieu, date, notes)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                if (query.isNotEmpty()) {
                                    IconButton(onClick = { viewModel.setQuery("") }) {
                                        Icon(Icons.Default.Close, contentDescription = "Effacer")
                                    }
                                }
                            }
                        )
                    } else {
                        Column {
                            Text("Bibliothèque de scans")
                            ConnectionLine(connection) { onOpenSettings() }
                        }
                    }
                },
                actions = {
                    if (searching) {
                        IconButton(onClick = {
                            searching = false
                            viewModel.setQuery("")
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Fermer la recherche")
                        }
                    } else {
                        IconButton(onClick = { searching = true }) {
                            Icon(Icons.Default.Search, contentDescription = "Rechercher")
                        }
                        Box {
                            IconButton(onClick = { sortMenuOpen = true }) {
                                Icon(Icons.Default.SwapVert, contentDescription = "Trier")
                            }
                            DropdownMenu(
                                expanded = sortMenuOpen,
                                onDismissRequest = { sortMenuOpen = false }
                            ) {
                                ScanSort.values().forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.label) },
                                        onClick = {
                                            viewModel.setSort(option)
                                            sortMenuOpen = false
                                        },
                                        leadingIcon = {
                                            if (option == sort) {
                                                Icon(Icons.Default.Check, contentDescription = null)
                                            } else {
                                                Spacer(Modifier.size(24.dp))
                                            }
                                        }
                                    )
                                }
                            }
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "Réglages")
                        }
                    }
                }
            )
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                scans.isEmpty() && isRefreshing -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                scans.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                when {
                                    query.isNotBlank() -> "Aucun résultat pour « $query »"
                                    isConnected -> "Aucun scan sur le Jetson"
                                    else -> "Aucun scan en cache"
                                },
                                style = MaterialTheme.typography.bodyLarge
                            )
                            if (listError != null) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    listError!!,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "Tirez vers le bas pour rafraîchir",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (listError != null) {
                            item {
                                Text(
                                    "Hors-ligne : liste en cache. ($listError)",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                            }
                        }
                        items(scans, key = { it.name }) { scan ->
                            ScanRow(
                                scan = scan,
                                isConnected = isConnected,
                                progress = viewModel.downloadProgress[scan.name],
                                onDownload = {
                                    viewModel.downloadPcd(scan) { msg ->
                                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                    }
                                },
                                onCancelDownload = { viewModel.cancelDownload(scan.name) },
                                onOpen = { onOpenPcd(scan.name) },
                                onEditNotes = { notesScan = scan },
                                onViewConfig = { configScan = scan }
                            )
                        }
                    }
                }
            }
        }
    }

    notesScan?.let { scan ->
        NotesDialog(
            scan = scan,
            viewModel = viewModel,
            onDismiss = { notesScan = null }
        )
    }

    configScan?.let { scan ->
        ConfigDialog(
            scan = scan,
            viewModel = viewModel,
            onDismiss = { configScan = null }
        )
    }
}

@Composable
private fun ConnectionLine(connection: ConnectionState, onClick: () -> Unit) {
    val (color, label) = when (connection) {
        is ConnectionState.Connected -> MaterialTheme.colorScheme.primary to "Connecté"
        is ConnectionState.Connecting -> MaterialTheme.colorScheme.tertiary to "Connexion…"
        is ConnectionState.Offline -> MaterialTheme.colorScheme.error to "Hors-ligne"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .background(color, CircleShape)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ScanRow(
    scan: ScanInfo,
    isConnected: Boolean,
    progress: Float?,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onOpen: () -> Unit,
    onEditNotes: () -> Unit,
    onViewConfig: () -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            // Title + subtitle
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        scan.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val subtitle = listOfNotNull(
                        scan.date.ifBlank { null },
                        scan.location.ifBlank { null },
                        scan.run.ifBlank { null }?.let { "run $it" }
                    ).joinToString(" • ")
                    if (subtitle.isNotEmpty()) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (scan.localOnly) {
                    AssistChip(onClick = {}, label = { Text("Local") })
                }
            }

            Spacer(Modifier.height(8.dp))

            // Info line: bag + pcd sizes
            val info = buildList {
                if (scan.bag.present) add("Bag ${scan.bag.sizeHuman}")
                if (scan.pcd.present) add("PCD ${scan.pcd.sizeHuman}")
            }.joinToString("   ·   ")
            if (info.isNotEmpty()) {
                Text(info, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
            }

            // Download progress
            if (progress != null) {
                if (progress < 0f) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (progress < 0f) "Téléchargement…" else "Téléchargement ${(progress * 100).toInt()} %",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onCancelDownload) { Text("Annuler") }
                }
            }

            // Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // PCD action: Open / Download
                if (scan.pcd.present || scan.downloadedLocally) {
                    if (scan.downloadedLocally) {
                        Button(onClick = onOpen) {
                            Icon(Icons.Default.Visibility, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Ouvrir")
                        }
                    } else if (progress == null) {
                        Button(onClick = onDownload, enabled = isConnected) {
                            Icon(Icons.Default.CloudDownload, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Télécharger")
                        }
                    }
                }

                Spacer(Modifier.weight(1f))

                // Config (read-only, online)
                if (scan.config.present) {
                    IconButton(onClick = onViewConfig, enabled = isConnected) {
                        Icon(Icons.Default.Description, contentDescription = "Voir la config")
                    }
                }
                // Notes (edit, online)
                IconButton(onClick = onEditNotes, enabled = isConnected) {
                    Icon(
                        if (scan.notes.present) Icons.Default.EditNote else Icons.AutoMirrored.Filled.NoteAdd,
                        contentDescription = "Notes"
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Config viewer (read-only YAML)
// ---------------------------------------------------------------------------

@Composable
private fun ConfigDialog(
    scan: ScanInfo,
    viewModel: ScanLibraryViewModel,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var text by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(scan.name) {
        viewModel.fetchConfig(scan.name).fold(
            onSuccess = { text = it },
            onFailure = { error = it.message ?: "Erreur" }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Config — ${scan.name}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Box(Modifier.heightIn(max = 420.dp)) {
                when {
                    error != null -> Text("Erreur : $error", color = MaterialTheme.colorScheme.error)
                    text == null -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    else -> Text(
                        text!!,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { text?.let { clipboard.setText(AnnotatedString(it)) } },
                enabled = text != null
            ) { Text("Copier") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fermer") } }
    )
}

// ---------------------------------------------------------------------------
// Notes form
// ---------------------------------------------------------------------------

@Composable
private fun NotesDialog(
    scan: ScanInfo,
    viewModel: ScanLibraryViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }

    var site by remember { mutableStateOf("") }
    var issues by remember { mutableStateOf("") }
    var free by remember { mutableStateOf("") }

    LaunchedEffect(scan.name) {
        viewModel.fetchNotes(scan.name).fold(
            onSuccess = { json ->
                site = json.optString("site", "")
                issues = json.optString("issues", "")
                free = json.optString("free", "")
                loading = false
            },
            onFailure = {
                Toast.makeText(context, "Notes injoignables : ${it.message}", Toast.LENGTH_LONG).show()
                loading = false
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Notes — ${scan.name}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            if (loading) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(site, { site = it }, label = { Text("Site") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(issues, { issues = it }, label = { Text("Problèmes rencontrés") }, modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp))
                    OutlinedTextField(free, { free = it }, label = { Text("Notes libres") }, modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !loading && !saving,
                onClick = {
                    saving = true
                    val json = JSONObject().apply {
                        put("site", site)
                        put("issues", issues)
                        put("free", free)
                    }
                    scope.launch {
                        viewModel.saveNotes(scan.name, json).fold(
                            onSuccess = {
                                Toast.makeText(context, "Notes enregistrées", Toast.LENGTH_SHORT).show()
                                viewModel.refresh()
                                onDismiss()
                            },
                            onFailure = {
                                saving = false
                                Toast.makeText(context, "Échec : ${it.message}", Toast.LENGTH_LONG).show()
                            }
                        )
                    }
                }
            ) { Text(if (saving) "…" else "Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}
