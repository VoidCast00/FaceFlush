package io.github.voidcast00.faceflush.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Grain
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.voidcast00.faceflush.EditorEvent
import io.github.voidcast00.faceflush.EditorState
import io.github.voidcast00.faceflush.EditorViewModel
import io.github.voidcast00.faceflush.R
import io.github.voidcast00.faceflush.detect.DetectorKind
import io.github.voidcast00.faceflush.effects.EditorMode
import io.github.voidcast00.faceflush.effects.ParamSpec
import io.github.voidcast00.faceflush.effects.Params
import io.github.voidcast00.faceflush.effects.RegionMode
import io.github.voidcast00.faceflush.effects.Strength
import io.github.voidcast00.faceflush.effects.Technique
import io.github.voidcast00.faceflush.max.MaxPrivacyWorker
import io.github.voidcast00.faceflush.ui.theme.SectionLabel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Shows message events as snackbars and hands share events to [onShare]. */
@Composable
fun CollectMessages(
    events: Flow<EditorEvent>,
    snackbar: SnackbarHostState,
    onShare: (EditorEvent.Share) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(events) {
        events.collect { event ->
            when (event) {
                is EditorEvent.Message -> scope.launch { snackbar.showSnackbar(context.getString(event.text)) }
                is EditorEvent.Share -> onShare(event)
            }
        }
    }
}

private val Technique.icon: ImageVector
    get() = when (this) {
        Technique.PIXELATE -> Icons.Outlined.GridOn
        Technique.BLUR -> Icons.Outlined.BlurOn
        Technique.MASK -> Icons.Outlined.Circle
        Technique.DEAD_PIXELS -> Icons.Outlined.Grain
        Technique.LANDMARKS -> Icons.Outlined.VisibilityOff
        Technique.INVISIBLE -> Icons.Outlined.Shield
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(state: EditorState, vm: EditorViewModel, onShare: (EditorEvent.Share) -> Unit) {
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(vm.events, snackbar, onShare)
    var showBoxes by rememberSaveable { mutableStateOf(true) }
    var showOriginal by remember { mutableStateOf(false) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.Black) },
                navigationIcon = {
                    IconButton(onClick = vm::close) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    ModeSwitch(state.mode) { vm.setMode(it) }
                    DetectorMenu(state.detector, vm::setDetector)
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = { ExportBar(state, onSave = vm::save, onShare = vm::share) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val previewHeight = maxHeight * 0.42f
            Column(Modifier.fillMaxSize()) {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth()
                        .height(previewHeight),
                ) {
                    Column {
                        Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp)) {
                            if (state.original == null) {
                                AsciiArt(
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                                    accent = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                    style = GlitchStyle.Scan,
                                    faceOnly = true,
                                    modifier = Modifier.align(Alignment.Center).width(150.dp),
                                )
                            } else {
                                PhotoView(
                                    state = state,
                                    showOriginal = showOriginal,
                                    showBoxes = showBoxes,
                                    onToggleFace = vm::toggleFace,
                                    onRemoveManual = vm::removeManualBox,
                                    onAddManual = vm::addManualBox,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        PreviewToolbar(
                            showBoxes = showBoxes,
                            onShowBoxesChange = { showBoxes = it },
                            onComparePressed = { showOriginal = it },
                            onExpand = { fullscreen = true },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        )
                    }
                }
                Controls(
                    state = state,
                    vm = vm,
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 20.dp),
                )
            }
        }
    }

    if (fullscreen && state.original != null) {
        FullscreenPreview(
            state = state,
            showBoxes = showBoxes,
            onShowBoxesChange = { showBoxes = it },
            onDismiss = { fullscreen = false },
        )
    }
}

@Composable
private fun ExportBar(state: EditorState, onSave: () -> Unit, onShare: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val enabled = state.original != null && !state.exporting && !state.detecting
            Box(Modifier.weight(1f).height(52.dp)) {
                Button(onClick = onSave, enabled = enabled, modifier = Modifier.fillMaxSize()) {
                    Icon(Icons.Outlined.Download, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.save), style = MaterialTheme.typography.titleMedium)
                }
                // Saving "flushes" the button: glitch blocks sweep across it, then burst when done
                GlitchSweep(
                    active = state.exporting,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.matchParentSize().clip(ButtonDefaults.shape),
                )
            }
            FilledTonalButton(onClick = onShare, enabled = enabled, modifier = Modifier.weight(1f).height(52.dp)) {
                Icon(Icons.Outlined.Share, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.share), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun ModeSwitch(mode: EditorMode, onSwitch: (EditorMode) -> Unit) {
    val toInvisible = mode == EditorMode.VISIBLE
    TextButton(onClick = { onSwitch(if (toInvisible) EditorMode.INVISIBLE else EditorMode.VISIBLE) }) {
        Icon(if (toInvisible) Icons.Outlined.Shield else Icons.Outlined.GridOn, stringResource(R.string.switch_mode), Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(if (toInvisible) R.string.switch_to_invisible else R.string.switch_to_visible))
    }
}

@Composable
private fun DetectorMenu(current: DetectorKind, onSelect: (DetectorKind) -> Unit) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) {
        Icon(Icons.Filled.MoreVert, stringResource(R.string.settings))
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        Text(
            stringResource(R.string.detector_title).uppercase(),
            style = SectionLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        listOf(
            DetectorKind.YUNET to R.string.detector_yunet,
            DetectorKind.PLATFORM to R.string.detector_platform,
        ).forEach { (kind, label) ->
            DropdownMenuItem(
                text = { Text(stringResource(label)) },
                leadingIcon = { RadioButton(selected = kind == current, onClick = null) },
                onClick = {
                    open = false
                    onSelect(kind)
                },
            )
        }
    }
}

@Composable
private fun SectionHeader(text: Int, modifier: Modifier = Modifier) {
    Text(
        stringResource(text).uppercase(),
        style = SectionLabel,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(bottom = 8.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun Controls(state: EditorState, vm: EditorViewModel, modifier: Modifier = Modifier) {
    val invisible = state.mode == EditorMode.INVISIBLE
    Column(modifier, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (invisible) {
            InvisibleHeader(state.technique)
        } else {
            Column {
                SectionHeader(R.string.technique)
                TechniqueRow(state.technique, vm::selectTechnique)
                Text(
                    stringResource(state.technique.description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }

        Column {
            SectionHeader(R.string.apply_to)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // The invisible cloak only shows the modes it supports
                RegionMode.entries.filter { !invisible || it in state.technique.regionModes }.forEach { mode ->
                    FilterChip(
                        selected = state.regionMode == mode,
                        onClick = { vm.selectRegionMode(mode) },
                        enabled = mode in state.technique.regionModes,
                        label = { Text(stringResource(mode.label)) },
                    )
                }
            }
            RegionHint(state)
        }

        Column {
            SectionHeader(R.string.strength)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Strength.entries.forEachIndexed { i, strength ->
                    SegmentedButton(
                        selected = state.strength == strength,
                        onClick = { vm.selectStrength(strength) },
                        shape = SegmentedButtonDefaults.itemShape(i, Strength.entries.size),
                    ) { Text(stringResource(strength.label)) }
                }
            }
            if (invisible) LiveStat(state.params)
        }

        MoreSettings(state, vm)

        if (state.technique == Technique.INVISIBLE) {
            MaxPrivacyCard(state, onStart = vm::startMax, onCancel = vm::cancelMax)
            FunExtras(state, vm)
        }
    }

    // The honest explainer, shown the first time the Invisible technique is picked
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE) }
    var showInfo by remember { mutableStateOf(false) }
    LaunchedEffect(state.technique) {
        if (state.technique == Technique.INVISIBLE && !prefs.getBoolean(KEY_INFO_SEEN, false)) showInfo = true
    }
    if (showInfo) {
        val close = {
            prefs.edit().putBoolean(KEY_INFO_SEEN, true).apply()
            showInfo = false
        }
        InvisibleInfoSheet(
            minutesPerFace = MaxPrivacyWorker.estimateMinutes(1),
            totalMinutes = 0,
            requireConsent = false,
            onDismiss = close,
            onConfirm = close,
        )
    }
}

private const val KEY_INFO_SEEN = "invisible_info_seen"

@Composable
private fun TechniqueRow(selected: Technique, onSelect: (Technique) -> Unit) {
    val colors = MaterialTheme.colorScheme
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(end = 16.dp)) {
        items(Technique.visible) { technique ->
            val isSelected = technique == selected
            Surface(
                onClick = { onSelect(technique) },
                shape = RoundedCornerShape(20.dp),
                color = if (isSelected) colors.secondaryContainer else colors.surfaceContainer,
                border = if (isSelected) BorderStroke(1.dp, colors.secondary) else null,
                modifier = Modifier.width(104.dp),
            ) {
                Column(Modifier.padding(12.dp)) {
                    // The portrait from the home screen, hidden the way this technique hides faces.
                    // Only the selected one moves, and picking it plays a short glitch burst
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) colors.secondary.copy(alpha = 0.25f) else colors.surfaceContainerHighest),
                        contentAlignment = Alignment.Center,
                    ) {
                        AsciiArt(
                            color = (if (isSelected) colors.onSecondaryContainer else colors.onSurface).copy(alpha = 0.35f),
                            accent = if (isSelected) colors.secondary else colors.primary.copy(alpha = 0.8f),
                            style = GlitchStyle.Hidden(technique),
                            faceOnly = true,
                            animate = isSelected,
                            burstKey = if (isSelected) technique else null,
                            modifier = Modifier.height(56.dp),
                        )
                        Icon(
                            technique.icon, null,
                            tint = if (isSelected) colors.secondary else colors.primary,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).size(16.dp),
                        )
                    }
                    Text(
                        stringResource(technique.title),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isSelected) colors.onSecondaryContainer else colors.onSurface,
                        minLines = 2,
                        maxLines = 2,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MoreSettings(state: EditorState, vm: EditorViewModel) {
    ExpandableCard(Icons.Outlined.Tune, R.string.more_settings) {
        state.technique.params
            .filter { !it.extra }
            .filter { it.key != Params.SHAPE || state.regionMode != RegionMode.WHOLE_IMAGE }
            .forEach { spec ->
                ParamControl(spec, state.params[spec.key] ?: spec.min) { vm.setParam(spec.key, it) }
            }
    }
}

/** Settings that only change the look (rainbow style, face shape), each with its honest warning. */
@Composable
private fun FunExtras(state: EditorState, vm: EditorViewModel) {
    val extras = state.technique.params.filter { it.extra }
    if (extras.isEmpty()) return
    ExpandableCard(Icons.Outlined.AutoAwesome, R.string.fun_extras, R.string.fun_extras_body) {
        extras.forEach { spec ->
            val value = state.params[spec.key] ?: spec.min
            Column {
                ParamControl(spec, value) { vm.setParam(spec.key, it) }
                val warning = when (spec.key) {
                    Params.RAINBOW -> R.string.rainbow_warning.takeIf { value >= 1f }
                    Params.MORPH -> R.string.morph_warning
                    else -> null
                }
                if (warning != null) {
                    Text(
                        stringResource(warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/** What the lab measured at the current strength, as a strip of squares and one line of text. */
@Composable
private fun LiveStat(params: Map<String, Float>) {
    val rainbow = (params[Params.RAINBOW] ?: 0f) >= 1f
    val count = LabStats.recognised(params[Params.VISIBILITY] ?: 0f, rainbow)
    Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StatStrip(count, Modifier.fillMaxWidth(), rainbow)
        Text(
            stringResource(R.string.stat_line, count),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Title card of the invisible half of the editor, with the glitching portrait as its picture. */
@Composable
private fun InvisibleHeader(technique: Technique) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(20.dp), color = colors.surfaceContainer) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(72.dp).clip(RoundedCornerShape(14.dp)).background(colors.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                AsciiArt(
                    color = colors.onSurface.copy(alpha = 0.35f),
                    accent = colors.primary.copy(alpha = 0.8f),
                    faceOnly = true,
                    modifier = Modifier.height(72.dp),
                )
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(stringResource(technique.title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(technique.description),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun ExpandableCard(icon: ImageVector, title: Int, subtitle: Int? = null, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    val arrow by animateFloatAsState(if (expanded) 180f else 0f, label = "arrow")
    Surface(shape = RoundedCornerShape(20.dp), color = colors.surfaceContainer) {
        Column {
            Surface(onClick = { expanded = !expanded }, color = colors.surfaceContainer) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.surfaceContainerHighest),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(icon, null, tint = colors.primary, modifier = Modifier.size(22.dp))
                    }
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                        if (subtitle != null) {
                            Text(stringResource(subtitle), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                    }
                    Icon(Icons.Outlined.ExpandMore, null, Modifier.rotate(arrow), tint = colors.onSurfaceVariant)
                }
            }
            AnimatedVisibility(expanded) {
                Column(
                    Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) { content() }
            }
        }
    }
}

@Composable
private fun RegionHint(state: EditorState) {
    val text = when {
        state.detecting -> stringResource(R.string.hint_detecting)
        state.regionMode == RegionMode.FACES -> {
            val n = state.faces.size
            pluralStringResource(R.plurals.hint_faces, n, n)
        }
        state.regionMode == RegionMode.MANUAL -> stringResource(R.string.hint_manual)
        else -> stringResource(R.string.hint_whole)
    }
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (state.detecting) {
            AsciiArt(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                accent = MaterialTheme.colorScheme.primary,
                style = GlitchStyle.Scan,
                faceOnly = true,
                modifier = Modifier.height(28.dp).padding(end = 8.dp),
            )
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ParamControl(spec: ParamSpec, value: Float, onChange: (Float) -> Unit) {
    Column {
        val options = spec.options
        if (options != null) {
            Text(stringResource(spec.label), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEachIndexed { i, label ->
                    FilterChip(
                        selected = value.roundToInt() == i,
                        onClick = { onChange(i.toFloat()) },
                        label = { Text(stringResource(label)) },
                    )
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(spec.label), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                val shown = if (spec.step < 1f) "%.2f".format(value) else value.roundToInt().toString()
                Text(shown, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            Slider(
                value = value.coerceIn(spec.min, spec.max),
                onValueChange = onChange,
                valueRange = spec.min..spec.max,
                steps = (((spec.max - spec.min) / spec.step).roundToInt() - 1).coerceAtLeast(0),
            )
        }
    }
}
