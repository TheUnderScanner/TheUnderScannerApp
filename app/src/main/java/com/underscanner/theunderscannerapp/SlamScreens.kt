package com.underscanner.theunderscannerapp

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

// ===========================================================================
// Settings section: default algo + default profile, and the way into the list
// ===========================================================================

@Composable
fun SlamSettingsSection(viewModel: SlamViewModel, onOpenProfiles: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { viewModel.refresh() }
    val algos = viewModel.algos
    val toast: (String?) -> Unit = { err ->
        Toast.makeText(context, err ?: "Enregistré", Toast.LENGTH_SHORT).show()
    }

    Text("SLAM", style = MaterialTheme.typography.titleMedium)
    Text(
        "Algorithme et profil de configuration utilisés par défaut au démarrage d'un scan. " +
            "Les profils sont stockés sur le Jetson.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    viewModel.error?.let {
        Text("Indisponible : $it", color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
    }
    ChoiceField(
        label = "Algorithme par défaut",
        value = algos?.byId(algos.default)?.label ?: "—",
        options = algos?.algos.orEmpty().map { it.id to it.label },
        enabled = algos != null,
        onSelect = { viewModel.setDefaultAlgo(it, toast) }
    )
    ChoiceField(
        label = "Profil par défaut (${viewModel.algoInfo?.label ?: "—"})",
        value = viewModel.defaultProfile ?: "—",
        options = viewModel.profiles.map { it.name to it.name },
        enabled = viewModel.profiles.isNotEmpty(),
        onSelect = { viewModel.setDefaultProfile(it, toast) }
    )
    OutlinedButton(onClick = onOpenProfiles, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Tune, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("Profils de configuration…")
    }
}

/** A labelled value that opens a menu of (id, label) options. */
@Composable
fun ChoiceField(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    enabled: Boolean = true,
    onSelect: (String) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedCard(
            modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { open = true }
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (id, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onSelect(id) })
            }
        }
    }
}

// ===========================================================================
// Profile list
// ===========================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlamProfilesScreen(
    viewModel: SlamViewModel,
    onBack: () -> Unit,
    onOpenProfile: (algo: String, name: String) -> Unit
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { viewModel.refresh() }
    val algo = viewModel.algo
    var nameDialog by remember { mutableStateOf<NameAction?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    val toast: (String?) -> Unit = { err ->
        Toast.makeText(context, err ?: "OK", Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Profils SLAM") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Actualiser")
                    }
                }
            )
        },
        floatingActionButton = {
            if (algo != null && viewModel.profiles.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { nameDialog = NameAction.Copy(SlamProfileInfo.FACTORY) },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("Nouveau (copie d'usine)") }
                )
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            val algos = viewModel.algos
            if (algos != null && algos.algos.size > 1) {
                ChoiceField(
                    label = "Algorithme",
                    value = viewModel.algoInfo?.label ?: "—",
                    options = algos.algos.map { it.id to it.label },
                    onSelect = { viewModel.selectAlgo(it) }
                )
                Spacer(Modifier.height(8.dp))
            } else viewModel.algoInfo?.let {
                Text(it.label, style = MaterialTheme.typography.titleMedium)
                Text(it.description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
            }
            viewModel.error?.let {
                Text("Indisponible : $it", color = MaterialTheme.colorScheme.error)
            }
            if (viewModel.loading && viewModel.profiles.isEmpty()) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 88.dp)
            ) {
                items(viewModel.profiles, key = { it.name }) { p ->
                    ProfileRow(
                        profile = p,
                        onOpen = { algo?.let { onOpenProfile(it, p.name) } },
                        onSetDefault = { viewModel.setDefaultProfile(p.name, toast) },
                        onCopy = { nameDialog = NameAction.Copy(p.name) },
                        onRename = { nameDialog = NameAction.Rename(p.name) },
                        onDelete = { deleting = p.name }
                    )
                }
            }
        }
    }

    nameDialog?.let { action ->
        ProfileNameDialog(
            action = action,
            taken = viewModel.profiles.map { it.name }.toSet(),
            onDismiss = { nameDialog = null },
            onConfirm = { newName ->
                nameDialog = null
                when (action) {
                    is NameAction.Copy -> viewModel.copy(action.from, newName) { err ->
                        toast(err)
                        if (err == null && algo != null) onOpenProfile(algo, newName)
                    }
                    is NameAction.Rename -> viewModel.rename(action.from, newName, toast)
                }
            }
        )
    }
    deleting?.let { name ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Supprimer « $name » ?") },
            text = { Text("Le profil est supprimé du Jetson. Les scans déjà faits gardent leur config.") },
            confirmButton = {
                TextButton(onClick = { deleting = null; viewModel.delete(name, toast) }) {
                    Text("Supprimer", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Annuler") } }
        )
    }
}

private sealed class NameAction(val from: String) {
    class Copy(from: String) : NameAction(from)
    class Rename(from: String) : NameAction(from)
}

@Composable
private fun ProfileRow(
    profile: SlamProfileInfo,
    onOpen: () -> Unit,
    onSetDefault: () -> Unit,
    onCopy: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (profile.readonly) Icons.Default.Lock else Icons.Default.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(profile.name, style = MaterialTheme.typography.titleSmall)
                    if (profile.isDefault) {
                        Spacer(Modifier.width(8.dp))
                        Badge(containerColor = MaterialTheme.colorScheme.primary) { Text("par défaut") }
                    }
                }
                Text(
                    if (profile.readonly) "Config d'usine · lecture seule"
                    else "Modifié ${profile.updated?.replace('T', ' ') ?: "—"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Actions")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (!profile.isDefault) {
                        DropdownMenuItem(text = { Text("Définir par défaut") },
                            leadingIcon = { Icon(Icons.Default.Star, null) },
                            onClick = { menu = false; onSetDefault() })
                    }
                    DropdownMenuItem(text = { Text("Dupliquer") },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                        onClick = { menu = false; onCopy() })
                    if (!profile.readonly) {
                        DropdownMenuItem(text = { Text("Renommer") },
                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                            onClick = { menu = false; onRename() })
                        DropdownMenuItem(text = { Text("Supprimer") },
                            enabled = !profile.isDefault,
                            leadingIcon = { Icon(Icons.Default.Delete, null) },
                            onClick = { menu = false; onDelete() })
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileNameDialog(
    action: NameAction,
    taken: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val isCopy = action is NameAction.Copy
    var name by remember {
        mutableStateOf(if (isCopy) "" else action.from)
    }
    val problem = SlamViewModel.nameProblem(name.trim())
        ?: if (name.trim() in taken && name.trim() != action.from) "Ce nom existe déjà" else null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isCopy) "Dupliquer « ${action.from} »" else "Renommer « ${action.from} »") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nom du profil") },
                placeholder = { Text("voxel-0.3") },
                singleLine = true,
                isError = name.isNotEmpty() && problem != null,
                supportingText = { if (name.isNotEmpty() && problem != null) Text(problem) },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii
                )
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = problem == null && name.trim() != action.from
            ) { Text(if (isCopy) "Dupliquer" else "Renommer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

// ===========================================================================
// Profile editor
// ===========================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlamProfileEditorScreen(
    viewModel: SlamViewModel,
    algo: String,
    name: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val readonly = name == SlamProfileInfo.FACTORY

    var text by remember { mutableStateOf(TextFieldValue("")) }
    var saved by remember { mutableStateOf<String?>(null) }       // text as stored on the Jetson
    var loadError by remember { mutableStateOf<String?>(null) }
    var validation by remember { mutableStateOf<SlamValidation?>(null) }
    var validatedText by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }

    LaunchedEffect(algo, name) {
        viewModel.loadText(algo, name).fold(
            onSuccess = { saved = it; text = TextFieldValue(it) },
            onFailure = { loadError = SlamViewModel.describe(it) }
        )
    }

    val dirty = saved != null && text.text != saved
    val fresh = validation != null && validatedText == text.text
    val canSave = !readonly && dirty && fresh && validation?.ok == true && !busy
    val algoInfo = viewModel.algos?.byId(algo)

    fun validate() {
        busy = true
        val snapshot = text.text
        scope.launch {
            viewModel.validate(algo, snapshot).fold(
                onSuccess = { validation = it; validatedText = snapshot },
                onFailure = {
                    Toast.makeText(context, SlamViewModel.describe(it), Toast.LENGTH_LONG).show()
                }
            )
            busy = false
        }
    }

    fun save() {
        busy = true
        val snapshot = text.text
        scope.launch {
            viewModel.save(algo, name, snapshot).fold(
                onSuccess = { r ->
                    saved = r.text
                    text = TextFieldValue(r.text, TextRange(text.selection.start.coerceAtMost(r.text.length)))
                    validation = SlamValidation(true, r.issues, r.text, null)
                    validatedText = r.text
                    Toast.makeText(context, "Enregistré sur le Jetson", Toast.LENGTH_SHORT).show()
                },
                onFailure = { e ->
                    if (e is SlamConfigException) {
                        validation = SlamValidation(false, e.issues, null, null)
                        validatedText = snapshot
                    } else {
                        Toast.makeText(context, SlamViewModel.describe(e), Toast.LENGTH_LONG).show()
                    }
                }
            )
            busy = false
        }
    }

    BackHandler(enabled = dirty) { confirmLeave = true }

    val vScroll = rememberScrollState()
    val lineHeightPx = with(LocalDensity.current) { EDITOR_LINE_HEIGHT.toPx() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(name + if (dirty) " •" else "", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(algoInfo?.label ?: algo, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (dirty) confirmLeave = true else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    if (!readonly) {
                        IconButton(onClick = { confirmReset = true }, enabled = saved != null && !busy) {
                            Icon(Icons.Default.SettingsBackupRestore,
                                contentDescription = "Réinitialiser depuis l'usine")
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (!readonly) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { validate() },
                            enabled = saved != null && !busy,
                            modifier = Modifier.weight(1f)
                        ) { Text("Valider") }
                        Button(
                            onClick = { save() },
                            enabled = canSave,
                            modifier = Modifier.weight(1f)
                        ) { Text("Enregistrer") }
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            loadError?.let {
                Text("Impossible de charger : $it", color = MaterialTheme.colorScheme.error)
                return@Column
            }
            EditorStatus(readonly, dirty, fresh, validation, algoInfo)
            Spacer(Modifier.height(6.dp))

            val errorColor = MaterialTheme.colorScheme.error.copy(alpha = 0.22f)
            val warnColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f)
            val marks = remember(validation, fresh) {
                if (!fresh) emptyMap() else buildMap {
                    validation?.issues?.filter { it.where == "yaml" && it.line != null }
                        ?.sortedBy { it.isError }                 // errors win on a shared line
                        ?.forEach { put(it.line!!, if (it.isError) errorColor else warnColor) }
                }
            }
            CodeEditor(
                value = text,
                onValueChange = { text = it },
                readOnly = readonly || saved == null,
                lineMarks = marks,
                vScroll = vScroll,
                modifier = Modifier.weight(1f)
            )

            val issues = validation?.issues.orEmpty()
            if (fresh && issues.isNotEmpty()) {
                IssueList(
                    issues = issues,
                    modifier = Modifier.heightIn(max = 150.dp),
                    onClick = { issue ->
                        issue.line?.let { line ->
                            scope.launch { vScroll.animateScrollTo(((line - 3) * lineHeightPx).toInt().coerceAtLeast(0)) }
                            text = text.copy(selection = TextRange(lineStart(text.text, line)))
                        }
                    }
                )
            }
            if (readonly) {
                Text(
                    "Le profil usine est la référence de validation : il ne se modifie pas. " +
                        "Duplique-le depuis la liste pour créer un profil.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Repartir de l'usine ?") },
            text = { Text("Le texte de l'éditeur est remplacé par la config d'usine. Rien n'est " +
                "enregistré tant que tu ne valides pas puis n'enregistres pas.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    scope.launch {
                        viewModel.loadText(algo, SlamProfileInfo.FACTORY).fold(
                            onSuccess = { text = TextFieldValue(it); validation = null },
                            onFailure = {
                                Toast.makeText(context, SlamViewModel.describe(it), Toast.LENGTH_LONG).show()
                            }
                        )
                    }
                }) { Text("Remplacer") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Annuler") } }
        )
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Abandonner les modifications ?") },
            text = { Text("Elles n'ont pas été enregistrées sur le Jetson.") },
            confirmButton = {
                TextButton(onClick = { confirmLeave = false; onBack() }) {
                    Text("Abandonner", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Rester") } }
        )
    }
}

/** One line telling where the editor stands: read-only / to validate / valid / refused. */
@Composable
private fun EditorStatus(
    readonly: Boolean,
    dirty: Boolean,
    fresh: Boolean,
    validation: SlamValidation?,
    algoInfo: SlamAlgoInfo?
) {
    val (icon, color, msg) = when {
        readonly -> Triple(Icons.Default.Lock, MaterialTheme.colorScheme.onSurfaceVariant,
            "Lecture seule")
        fresh && validation?.ok == false -> Triple(Icons.Default.Error, MaterialTheme.colorScheme.error,
            "Refusé : ${validation.errors.size} erreur(s)")
        fresh && validation?.ok == true -> Triple(Icons.Default.CheckCircle, MaterialTheme.colorScheme.primary,
            if (dirty) "Valide — prêt à enregistrer" else "Valide")
        dirty -> Triple(Icons.Default.Info, MaterialTheme.colorScheme.tertiary,
            "Modifié — à valider avant d'enregistrer")
        else -> Triple(Icons.Default.Info, MaterialTheme.colorScheme.onSurfaceVariant,
            "Enregistré sur le Jetson")
    }
    Column(Modifier.padding(top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(msg, style = MaterialTheme.typography.bodyMedium, color = color)
        }
        if (algoInfo != null && algoInfo.forced.isNotEmpty()) {
            Text(
                "Imposées par le backend à chaque scan : ${algoInfo.forced.joinToString(", ")}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun IssueList(issues: List<SlamIssue>, modifier: Modifier, onClick: (SlamIssue) -> Unit) {
    LazyColumn(modifier.fillMaxWidth().padding(top = 6.dp)) {
        items(issues.sortedWith(compareBy({ !it.isError }, { it.line ?: Int.MAX_VALUE }))) { issue ->
            Row(
                Modifier.fillMaxWidth().clickable(enabled = issue.line != null) { onClick(issue) }
                    .padding(vertical = 3.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    if (issue.isError) Icons.Default.Error else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (issue.isError) MaterialTheme.colorScheme.error
                           else MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    (issue.line?.let { "L$it  " } ?: "") + issue.msg,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

private val EDITOR_LINE_HEIGHT = 18.sp

/** Offset of the first character of 1-based [line] in [text]. */
internal fun lineStart(text: String, line: Int): Int {
    var idx = 0
    repeat((line - 1).coerceAtLeast(0)) {
        val nl = text.indexOf('\n', idx)
        if (nl < 0) return text.length
        idx = nl + 1
    }
    return idx
}

/**
 * Plain-text code editor: monospace, line-number gutter, no soft wrap (horizontal scroll), and
 * whole-line background marks (validation errors / warnings). The gutter and the text share
 * one vertical scroll and the same line height, so numbers stay aligned with their lines.
 */
@Composable
fun CodeEditor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    readOnly: Boolean,
    lineMarks: Map<Int, Color>,
    vScroll: androidx.compose.foundation.ScrollState,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val style = TextStyle(
        fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = EDITOR_LINE_HEIGHT,
        color = colors.onSurface
    )
    val lineCount = value.text.count { it == '\n' } + 1
    val digits = lineCount.toString().length
    val gutter = remember(lineCount, lineMarks, colors) {
        buildAnnotatedString {
            for (i in 1..lineCount) {
                val n = i.toString().padStart(digits)
                if (lineMarks.containsKey(i)) {
                    withStyle(SpanStyle(color = colors.error, fontWeight = FontWeight.Bold)) { append(n) }
                } else append(n)
                if (i < lineCount) append('\n')
            }
        }
    }
    val hScroll = rememberScrollState()

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, colors.outlineVariant),
        color = colors.surface
    ) {
        BoxWithConstraints {
            // Fill the rest of the row so a tap anywhere right of short lines focuses the
            // field; gutter ≈ digits × 0.6 em of 12 sp + its padding, plus the text padding.
            val minTextWidth = (maxWidth - (digits * 7.5f + 24f).dp).coerceAtLeast(0.dp)
            Row(Modifier.fillMaxSize().verticalScroll(vScroll)) {
                Text(
                    gutter,
                    style = style.copy(color = colors.onSurfaceVariant),
                    softWrap = false,
                    modifier = Modifier
                        .background(colors.surfaceVariant)
                        .padding(horizontal = 6.dp, vertical = 6.dp)
                )
                Box(Modifier.horizontalScroll(hScroll)) {
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        readOnly = readOnly,
                        textStyle = style,
                        cursorBrush = SolidColor(colors.primary),
                        visualTransformation = LineMarks(lineMarks),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Ascii
                        ),
                        modifier = Modifier
                            .widthIn(min = minTextWidth)
                            .padding(horizontal = 6.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

/** Paints whole lines (1-based) with a background; text and offsets are unchanged. */
private data class LineMarks(val marks: Map<Int, Color>) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (marks.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
        val b = AnnotatedString.Builder(text)
        var start = 0
        text.text.split('\n').forEachIndexed { i, l ->
            marks[i + 1]?.let { c -> b.addStyle(SpanStyle(background = c), start, start + l.length) }
            start += l.length + 1
        }
        return TransformedText(b.toAnnotatedString(), OffsetMapping.Identity)
    }
}
