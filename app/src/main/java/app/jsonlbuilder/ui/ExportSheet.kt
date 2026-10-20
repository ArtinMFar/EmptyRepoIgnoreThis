package app.jsonlbuilder.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import app.jsonlbuilder.data.Dataset
import app.jsonlbuilder.data.ExportFormat
import app.jsonlbuilder.data.Jsonl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(
    vm: AppViewModel,
    ds: Dataset,
    snackbar: SnackbarHostState,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val options = vm.exportOptions
    val lines = Jsonl.lines(ds.systemPrompt, ds.pairs(), options)
    val fileName = fileNameFor(ds.name)
    val buildContent = { Jsonl.build(ds.systemPrompt, ds.pairs(), vm.exportOptions) }
    val left = ds.examples.count { !it.isEmpty } - lines.size

    fun closeThen(message: String) {
        scope.launch {
            sheetState.hide()
            onDismiss()
            snackbar.showSnackbar(message)
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri != null) scope.launch {
            val count = lines.size
            val ok = vm.writeToUri(uri, buildContent())
            closeThen(if (ok) "Saved ${examplesLabel(count)} to $fileName" else "Couldn't save the file")
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            Text("Export as JSONL", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "${examplesLabel(lines.size)} → $fileName",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionTitle("Format")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ExportFormat.entries.forEach { format ->
                    FormatOption(
                        format = format,
                        selected = format == options.format,
                        onClick = { vm.updateExportOptions(options.copy(format = format)) },
                    )
                }
            }
            if (ds.systemPrompt.isNotBlank() && !options.format.usesSystemPrompt) {
                Hint("Your system prompt is only included in the Chat messages format.")
            }

            SectionTitle("Options")
            SwitchRow(
                title = "Skip incomplete examples",
                subtitle = "Leave out examples missing an input or a response",
                checked = options.skipIncomplete,
                onCheckedChange = { vm.updateExportOptions(options.copy(skipIncomplete = it)) },
            )
            SwitchRow(
                title = "Trim whitespace",
                subtitle = "Remove spaces and blank lines at the start and end of each text",
                checked = options.trimWhitespace,
                onCheckedChange = { vm.updateExportOptions(options.copy(trimWhitespace = it)) },
            )
            if (options.skipIncomplete && left > 0) {
                Hint("${examplesLabel(left)} will be skipped because ${if (left == 1) "it is" else "they are"} incomplete.")
            }

            SectionTitle("Preview")
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(14.dp),
                ) {
                    if (lines.isEmpty()) {
                        Text(
                            "Nothing to export yet — fill in at least one input and response.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        lines.take(3).forEach { line ->
                            Text(
                                if (line.length > 400) line.take(400) + "…" else line,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Clip,
                            )
                        }
                        if (lines.size > 3) {
                            Text(
                                "… ${lines.size - 3} more",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { saveLauncher.launch(fileName) },
                enabled = lines.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Icon(Icons.Rounded.SaveAlt, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Save .jsonl file")
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            shareJsonl(context, fileName, buildContent())
                            sheetState.hide()
                            onDismiss()
                        }
                    },
                    enabled = lines.isNotEmpty(),
                    modifier = Modifier.weight(1f).height(48.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Share")
                }
                OutlinedButton(
                    onClick = {
                        val copied = copyToClipboard(context, fileName, buildContent())
                        closeThen(if (copied) "Copied ${examplesLabel(lines.size)} to clipboard" else "Too large to copy — use Save or Share instead")
                    },
                    enabled = lines.isNotEmpty(),
                    modifier = Modifier.weight(1f).height(48.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Copy")
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 24.dp, bottom = 10.dp),
    )
}

@Composable
private fun FormatOption(format: ExportFormat, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = shape,
            )
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(start = 4.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(12.dp))
        Column(Modifier.weight(1f)) {
            Text(format.label, style = MaterialTheme.typography.titleSmall)
            Text(
                format.sample,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .toggleable(value = checked, onValueChange = onCheckedChange, role = Role.Switch)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun Hint(text: String) {
    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Top) {
        Icon(
            Icons.Outlined.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(16.dp).padding(top = 2.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
    }
}

fun fileNameFor(name: String): String {
    val base = name.trim()
        .replace(Regex("[^\\p{L}\\p{N}._ -]"), "")
        .replace(Regex("\\s+"), "_")
        .trim('_', '.', '-')
        .take(60)
    return base.ifEmpty { "dataset" } + ".jsonl"
}

private suspend fun shareJsonl(context: Context, fileName: String, content: String) {
    val uri = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "exports").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }
        val file = File(dir, fileName).apply { writeText(content) }
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/jsonl"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, fileName)
        clipData = ClipData.newRawUri(fileName, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share $fileName"))
}

private fun copyToClipboard(context: Context, label: String, content: String): Boolean = try {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText(label, content))
    true
} catch (_: RuntimeException) {
    // Very large clips exceed the binder transaction limit.
    false
}
