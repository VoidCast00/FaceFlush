package io.github.voidcast00.faceflush.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.voidcast00.faceflush.CleanState
import io.github.voidcast00.faceflush.R
import io.github.voidcast00.faceflush.io.Found

private val Found.label
    get() = when (this) {
        Found.LOCATION -> R.string.found_location
        Found.CAMERA -> R.string.found_camera
        Found.DATE -> R.string.found_date
        Found.TEXT -> R.string.found_text
        Found.EXTRA -> R.string.found_extra
        Found.OTHER -> R.string.found_other
    }

/**
 * Progress and result of "Remove metadata only". While it works the portrait fills up with glitch;
 * afterwards it lists what was found and removed. It can only be closed once the work is done.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CleanSheet(state: CleanState, onDismiss: () -> Unit) {
    if (state == CleanState.Idle) return
    val current = rememberUpdatedState(state)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || current.value is CleanState.Done },
    )
    val colors = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = { if (current.value is CleanState.Done) onDismiss() }, sheetState = sheetState) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val progress = when (state) {
                is CleanState.Working -> state.done / state.total.coerceAtLeast(1).toFloat()
                else -> 1f
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsciiArt(
                    color = colors.onSurface.copy(alpha = 0.3f),
                    accent = colors.primary.copy(alpha = 0.85f),
                    style = GlitchStyle.Progress(progress),
                    faceOnly = true,
                    burstKey = if (state is CleanState.Done) state else null,
                    modifier = Modifier.height(64.dp).padding(end = 14.dp),
                )
                Text(
                    when (state) {
                        is CleanState.Working -> stringResource(R.string.clean_working, state.done + 1, state.total)
                        else -> stringResource(R.string.clean_done_title)
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Black,
                )
            }
            when (state) {
                is CleanState.Working -> LinearProgressIndicator({ progress }, Modifier.fillMaxWidth())
                is CleanState.Done -> Done(state, onDismiss)
                CleanState.Idle -> Unit
            }
        }
    }
}

@Composable
private fun Done(state: CleanState.Done, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column {
            Text(pluralStringResource(R.plurals.clean_saved, state.saved, state.saved), style = MaterialTheme.typography.bodyLarge)
            if (state.failed > 0) {
                Text(
                    pluralStringResource(R.plurals.clean_failed, state.failed, state.failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.error,
                )
            }
            if (state.converted > 0) {
                Text(
                    pluralStringResource(R.plurals.clean_converted, state.converted, state.converted),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.clean_found_title), style = MaterialTheme.typography.titleSmall, color = colors.primary)
            if (state.found.isEmpty()) {
                Text(stringResource(R.string.clean_found_none), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            Found.entries.filter { it in state.found }.forEach { found ->
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Outlined.CheckCircle, null, tint = colors.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(found.label), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Text(stringResource(R.string.clean_location_note), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        Text(stringResource(R.string.clean_originals_note), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(stringResource(R.string.info_ok))
        }
    }
}
