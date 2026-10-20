package app.imagetosvg.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.imagetosvg.R
import app.imagetosvg.shapes.ShapeKind
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: AppViewModel) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        for (message in vm.messages) snackbar.showSnackbar(message)
    }

    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.open(uri)
    }
    val browseLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.open(uri)
    }
    val choose = { pickLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val browse = { browseLauncher.launch(arrayOf("image/*")) }

    var saving by rememberSaveable { mutableStateOf(Mode.EXACT) }
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/svg+xml"),
    ) { uri ->
        if (uri != null) scope.launch {
            val ok = vm.saveTo(uri, saving)
            snackbar.showSnackbar(if (ok) "Saved ${vm.fileName(saving)}" else "Couldn't save the file")
        }
    }
    val onSave = { m: Mode ->
        saving = m
        saveLauncher.launch(vm.fileName(m))
    }
    val onShare = { m: Mode -> scope.launch { shareSvg(context, vm, m) }; Unit }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Image to SVG", fontWeight = FontWeight.SemiBold) },
                actions = {
                    if (vm.current != null) {
                        TextButton(onClick = choose) {
                            Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("New image")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val img = vm.current
        when {
            vm.loading -> Column(
                Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("Opening image…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            img == null -> EmptyState(Modifier.padding(padding), onChoose = choose, onBrowse = browse)

            else -> ImageScreen(vm, img, Modifier.padding(padding), onSave, onShare)
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier, onChoose: () -> Unit, onBrowse: () -> Unit) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(96.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.height(24.dp))
        Text("Turn an image into an SVG", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Logos, icons, screenshots, pixel art or photos.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onChoose, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp)) {
            Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Choose an image")
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onBrowse) {
            Icon(Icons.Rounded.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Browse files")
        }
        Spacer(Modifier.height(32.dp))
        Explainer(
            Icons.Rounded.Verified,
            "Exact",
            "Every pixel becomes part of a vector shape, so the SVG matches the image 100%. " +
                "The app re-renders the SVG and checks every pixel to prove it.",
        )
        Spacer(Modifier.height(16.dp))
        Explainer(
            Icons.Rounded.Casino,
            "Brute force",
            "Throws random shapes at the picture and keeps the ones that bring it closer. " +
                "Small, artistic files, but practically never an exact match.",
        )
    }
}

@Composable
private fun Explainer(icon: ImageVector, title: String, text: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ImageScreen(
    vm: AppViewModel,
    img: OpenImage,
    modifier: Modifier,
    onSave: (Mode) -> Unit,
    onShare: (Mode) -> Unit,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp),
    ) {
        val aspect = img.width.toFloat() / img.height
        val exact = vm.exact
        val brute = vm.brutePreview
        when {
            vm.mode == Mode.EXACT && exact is ExactState.Done ->
                PreviewCard(exact.rendered, img.preview, "SVG", aspect)
            vm.mode == Mode.BRUTE_FORCE && brute != null ->
                PreviewCard(
                    brute, img.preview, "SVG · ${count(vm.shapeCount)} shapes", aspect,
                    version = vm.bruteVersion, crispWhenEnlarged = false,
                )
            else -> PreviewCard(img.preview, img.preview, "Original", aspect)
        }

        Spacer(Modifier.height(12.dp))
        Text(img.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            "${img.width} × ${img.height} px · ${count(img.analysis.colors)} " +
                if (img.analysis.colors == 1) "colour" else "colours",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = vm.mode == Mode.EXACT,
                onClick = { vm.selectMode(Mode.EXACT) },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
                icon = { SegmentedButtonDefaults.Icon(vm.mode == Mode.EXACT) { Icon(Icons.Rounded.Verified, null, Modifier.size(18.dp)) } },
            ) { Text("Exact") }
            SegmentedButton(
                selected = vm.mode == Mode.BRUTE_FORCE,
                onClick = { vm.selectMode(Mode.BRUTE_FORCE) },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
                icon = { SegmentedButtonDefaults.Icon(vm.mode == Mode.BRUTE_FORCE) { Icon(Icons.Rounded.Casino, null, Modifier.size(18.dp)) } },
            ) { Text("Brute force") }
        }

        Spacer(Modifier.height(16.dp))
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp)) {
                when (vm.mode) {
                    Mode.EXACT -> ExactPanel(vm, img, onSave, onShare)
                    Mode.BRUTE_FORCE -> BruteForcePanel(vm, onSave, onShare)
                }
            }
        }
    }
}

@Composable
private fun ExactPanel(vm: AppViewModel, img: OpenImage, onSave: (Mode) -> Unit, onShare: (Mode) -> Unit) {
    when (val state = vm.exact) {
        ExactState.Idle -> Button(onClick = vm::convertExact, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Make exact SVG")
        }

        is ExactState.TooBig -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.width(10.dp))
                Text("Big file ahead", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "This image has ${count(img.analysis.colors)} different colours, and an exact SVG has to " +
                    "describe every pixel of every one, so it will be about ${fileSize(state.estimatedBytes)}. " +
                    "Photos (especially JPEGs) have a slightly different colour on almost every pixel. " +
                    "Exact SVGs stay small for logos, icons, screenshots and pixel art.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = vm::convertExact, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text("Make it anyway (≈ ${fileSize(state.estimatedBytes)})")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { vm.selectMode(Mode.BRUTE_FORCE) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text("Try brute force instead")
            }
        }

        is ExactState.Working -> {
            Text(state.phase + "…", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(14.dp))
            val progress = state.progress
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                    drawStopIndicator = {},
                    gapSize = 0.dp,
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape), gapSize = 0.dp)
            }
        }

        is ExactState.Done -> {
            if (state.mismatched == 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "100% match",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "The SVG was rendered back and compared with the image: all " +
                        "${count(img.width.toLong() * img.height)} pixels are identical.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "${count(state.mismatched)} pixels differ",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth()) {
                Stat("File size", fileSize(state.bytes), Modifier.weight(1f))
                Stat("Colours", count(state.stats.colors), Modifier.weight(1f))
                Stat("Shapes", count(state.stats.outlines), Modifier.weight(1f))
            }
            Spacer(Modifier.height(20.dp))
            SaveAndShare(Mode.EXACT, enabled = true, onSave, onShare)
        }

        is ExactState.Failed -> {
            Text(state.message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(16.dp))
            Button(onClick = vm::convertExact, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Try again") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BruteForcePanel(vm: AppViewModel, onSave: (Mode) -> Unit, onShare: (Mode) -> Unit) {
    Text(
        "Tries random shapes and keeps each one that brings the drawing closer to the image. " +
            "It gets close quickly, but practically never becomes an exact copy: that's what Exact is for.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    SectionTitle("Shapes")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ShapeKind.entries.forEach { k ->
            FilterChip(selected = vm.kind == k, onClick = { vm.selectKind(k) }, label = { Text(k.label) })
        }
    }

    SectionTitle("Stop after")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AppViewModel.LIMITS.forEach { n ->
            FilterChip(
                selected = vm.limit == n,
                onClick = { vm.selectLimit(n) },
                label = { Text(if (n == 0) "No limit" else count(n)) },
            )
        }
    }

    val started = vm.brutePreview != null
    if (started) {
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                percent(vm.similarity),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "similar",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 3.dp),
            )
            Spacer(Modifier.weight(1f))
            Text(
                count(vm.shapeCount) + if (vm.limit != 0) " / ${count(vm.limit)}" else "",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        if (vm.limit != 0) {
            LinearProgressIndicator(
                progress = { (vm.shapeCount.toFloat() / vm.limit).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                drawStopIndicator = {},
                gapSize = 0.dp,
            )
        } else if (vm.running) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape), gapSize = 0.dp)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Pixels exactly right: ${percent(vm.exactFraction)}" + (vm.bruteFinished?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(Modifier.height(20.dp))
    val canContinue = vm.limit == 0 || vm.shapeCount < vm.limit
    when {
        vm.running -> Button(onClick = vm::stopBruteForce, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Icon(Icons.Rounded.Stop, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Stop")
        }
        !started -> Button(onClick = vm::startBruteForce, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Start")
        }
        else -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = vm::startBruteForce,
                enabled = canContinue && vm.bruteFinished != "Exact match",
                modifier = Modifier.weight(1f).height(52.dp),
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Continue")
            }
            OutlinedButton(onClick = vm::resetBruteForce, modifier = Modifier.weight(1f).height(52.dp)) {
                Icon(Icons.Rounded.Replay, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Start over")
            }
        }
    }
    if (started && !canContinue && !vm.running) {
        Hint("Pick a higher limit above to keep adding shapes.")
    }
    if (vm.shapeCount > 0) {
        Spacer(Modifier.height(10.dp))
        SaveAndShare(Mode.BRUTE_FORCE, enabled = true, onSave, onShare)
    }
}

@Composable
private fun SaveAndShare(m: Mode, enabled: Boolean, onSave: (Mode) -> Unit, onShare: (Mode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = { onSave(m) },
            enabled = enabled,
            modifier = Modifier.weight(1f).height(52.dp),
        ) {
            Icon(Icons.Rounded.SaveAlt, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Save SVG")
        }
        OutlinedButton(
            onClick = { onShare(m) },
            enabled = enabled,
            modifier = Modifier.weight(1f).height(52.dp),
        ) {
            Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Share")
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 20.dp, bottom = 6.dp),
    )
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

private suspend fun shareSvg(context: Context, vm: AppViewModel, m: Mode) {
    val file = vm.svgFile(m) ?: return
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/svg+xml"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, file.name)
        clipData = ClipData.newRawUri(file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share ${file.name}"))
}

fun count(n: Number): String = String.format(Locale.getDefault(), "%,d", n.toLong())

/** 0.9423 → "94.2%". Never rounds up to 100% unless it really is 100%. */
fun percent(fraction: Double): String {
    val tenths = kotlin.math.floor(fraction * 1000).toLong()
    return String.format(Locale.getDefault(), "%.1f%%", tenths / 10.0)
}

fun fileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.getDefault(), "%.0f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format(Locale.getDefault(), "%.1f GB", bytes / (1024.0 * 1024 * 1024))
}
