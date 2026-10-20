package app.jsonlbuilder.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.jsonlbuilder.data.Dataset
import app.jsonlbuilder.data.Example
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(
    vm: AppViewModel,
    ds: Dataset,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    var showExport by rememberSaveable { mutableStateOf(false) }
    var showRename by rememberSaveable { mutableStateOf(false) }
    var showDelete by rememberSaveable { mutableStateOf(false) }
    var focusId by remember { mutableStateOf<String?>(null) }

    val addExample: () -> Unit = {
        val ex = vm.addExample(ds)
        focusId = ex.id
        // Item 0 is the system prompt card, so example i sits at index i + 1.
        scope.launch { listState.animateScrollToItem(ds.examples.size) }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Column {
                        Text(ds.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${examplesLabel(ds.examples.size)} · ${ds.completeCount} ready",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    FilledTonalButton(
                        onClick = { showExport = true },
                        contentPadding = PaddingValues(start = 12.dp, end = 16.dp),
                    ) {
                        Icon(Icons.Rounded.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Export")
                    }
                    Box {
                        var menu by remember { mutableStateOf(false) }
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Rename dataset") },
                                leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, null) },
                                onClick = { menu = false; showRename = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete dataset") },
                                leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                                onClick = { menu = false; showDelete = true },
                            )
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = !WindowInsets.isImeVisible,
                enter = scaleIn() + fadeIn(),
                exit = scaleOut() + fadeOut(),
            ) {
                ExtendedFloatingActionButton(
                    onClick = addExample,
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text("Add example") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val layoutDirection = LocalLayoutDirection.current
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(padding)
                .imePadding(),
            contentPadding = PaddingValues(
                start = padding.calculateStartPadding(layoutDirection) + 16.dp,
                end = padding.calculateEndPadding(layoutDirection) + 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 104.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "system") {
                SystemPromptCard(ds = ds, onChange = { vm.changed(ds) })
            }

            itemsIndexed(ds.examples, key = { _, ex -> ex.id }) { index, ex ->
                ExampleCard(
                    number = index + 1,
                    ex = ex,
                    canMoveUp = index > 0,
                    canMoveDown = index < ds.examples.lastIndex,
                    requestFocus = focusId == ex.id,
                    onFocused = { focusId = null },
                    onChange = { vm.changed(ds) },
                    onDuplicate = { vm.duplicateExample(ds, ex) },
                    onMove = { by -> vm.moveExample(ds, ex, by) },
                    onDelete = {
                        val position = vm.removeExample(ds, ex)
                        if (!ex.isEmpty) scope.launch {
                            snackbar.currentSnackbarData?.dismiss()
                            val result = snackbar.showSnackbar(
                                message = "Example ${position + 1} deleted",
                                actionLabel = "Undo",
                                duration = SnackbarDuration.Short,
                            )
                            if (result == SnackbarResult.ActionPerformed) vm.restoreExample(ds, ex, position)
                        }
                    },
                    modifier = Modifier.animateItem(),
                )
            }

            item(key = "add") {
                OutlinedButton(
                    onClick = addExample,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add another example")
                }
            }
        }
    }

    if (showExport) {
        ExportSheet(vm = vm, ds = ds, snackbar = snackbar, onDismiss = { showExport = false })
    }

    if (showRename) {
        NameDialog(
            title = "Rename dataset",
            confirmLabel = "Save",
            initial = ds.name,
            onConfirm = { name ->
                vm.rename(ds, name)
                showRename = false
            },
            onDismiss = { showRename = false },
        )
    }

    if (showDelete) {
        ConfirmDeleteDialog(
            title = "Delete “${ds.name}”?",
            message = "This permanently removes ${examplesLabel(ds.examples.size)}. " +
                "Export it first if you want to keep a copy.",
            onConfirm = {
                showDelete = false
                onBack()
                vm.delete(ds)
            },
            onDismiss = { showDelete = false },
        )
    }
}

@Composable
private fun SystemPromptCard(ds: Dataset, onChange: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(ds.systemPrompt.isNotBlank()) }
    Card(
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Tune,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("System prompt", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (!expanded && ds.systemPrompt.isNotBlank()) ds.systemPrompt
                    else "Optional · used in Chat format",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp),
            )
        }
        if (expanded) {
            ChatField(
                value = ds.systemPrompt,
                onValueChange = { ds.systemPrompt = it; onChange() },
                placeholder = "e.g. You are a friendly support agent for Acme…",
                container = MaterialTheme.colorScheme.surfaceContainerHighest,
                minLines = 2,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            )
        }
    }
}

@Composable
private fun ExampleCard(
    number: Int,
    ex: Example,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    requestFocus: Boolean,
    onFocused: () -> Unit,
    onChange: () -> Unit,
    onDuplicate: () -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    if (requestFocus) {
        LaunchedEffect(Unit) {
            runCatching { focus.requestFocus() }
            onFocused()
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Example $number",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                ExampleStatus(ex)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = "Delete example $number",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    var menu by remember { mutableStateOf(false) }
                    IconButton(onClick = { menu = true }) {
                        Icon(
                            Icons.Rounded.MoreVert,
                            contentDescription = "More options for example $number",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Duplicate") },
                            leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                            onClick = { menu = false; onDuplicate() },
                        )
                        DropdownMenuItem(
                            text = { Text("Move up") },
                            leadingIcon = { Icon(Icons.Rounded.ArrowUpward, null) },
                            enabled = canMoveUp,
                            onClick = { menu = false; onMove(-1) },
                        )
                        DropdownMenuItem(
                            text = { Text("Move down") },
                            leadingIcon = { Icon(Icons.Rounded.ArrowDownward, null) },
                            enabled = canMoveDown,
                            onClick = { menu = false; onMove(1) },
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                            onClick = { menu = false; onDelete() },
                        )
                    }
                }
            }

            FieldLabel(Icons.Rounded.Person, "User input", MaterialTheme.colorScheme.onSurfaceVariant)
            ChatField(
                value = ex.input,
                onValueChange = { ex.input = it; onChange() },
                placeholder = "What the user says…",
                container = MaterialTheme.colorScheme.surfaceContainerHighest,
                minLines = 2,
                modifier = Modifier.focusRequester(focus),
            )

            Spacer(Modifier.height(14.dp))

            FieldLabel(Icons.Rounded.SmartToy, "Model response", MaterialTheme.colorScheme.primary)
            ChatField(
                value = ex.output,
                onValueChange = { ex.output = it; onChange() },
                placeholder = "How the model should reply…",
                container = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                minLines = 3,
            )
        }
    }
}

@Composable
private fun ExampleStatus(ex: Example) {
    when {
        ex.isComplete -> Icon(
            Icons.Rounded.CheckCircle,
            contentDescription = "Complete",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        ex.isEmpty -> Unit
        else -> Text(
            if (ex.input.isBlank()) "Needs input" else "Needs response",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
}

@Composable
private fun FieldLabel(icon: ImageVector, text: String, tint: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = tint)
    }
}

@Composable
private fun ChatField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    container: Color,
    minLines: Int,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text(placeholder) },
        minLines = minLines,
        shape = RoundedCornerShape(16.dp),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = container,
            unfocusedContainerColor = container,
            unfocusedBorderColor = Color.Transparent,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
        ),
    )
}
