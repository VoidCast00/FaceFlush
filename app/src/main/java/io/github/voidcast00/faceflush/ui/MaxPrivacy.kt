package io.github.voidcast00.faceflush.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.voidcast00.faceflush.EditorState
import io.github.voidcast00.faceflush.R
import io.github.voidcast00.faceflush.max.MaxPrivacyWorker
import kotlin.math.roundToInt

/** Start, progress and result of Max privacy, shown under the Invisible technique. */
@Composable
fun MaxPrivacyCard(state: EditorState, onStart: () -> Unit, onCancel: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    var sheet by remember { mutableStateOf<InfoMode?>(null) }
    val faces = state.faces.count { it.enabled }
    val minutes = MaxPrivacyWorker.estimateMinutes(maxOf(1, faces))

    // Android 13+ asks before showing the progress notification; the job runs either way
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onStart() }
    val start = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else onStart()
    }

    Surface(shape = RoundedCornerShape(20.dp), color = colors.surfaceContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(colors.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Security, null, tint = colors.primary, modifier = Modifier.size(22.dp))
                }
                Text(
                    stringResource(R.string.max_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).padding(start = 14.dp),
                )
            }
            Text(
                stringResource(R.string.max_body),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            val max = state.max
            when {
                max.running -> {
                    val percent = max.percent
                    if (percent == null) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(stringResource(R.string.max_starting), style = MaterialTheme.typography.labelLarge)
                    } else {
                        LinearProgressIndicator({ percent / 100f }, Modifier.fillMaxWidth())
                        Text(
                            stringResource(R.string.max_running, percent, ((max.secondsLeft ?: 0) + 59) / 60),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                }
                state.maxApplied > 0 -> {
                    val eps = state.faces.firstNotNullOfOrNull { it.pattern }?.eps ?: 0f
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Outlined.CheckCircle, null, tint = colors.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            pluralStringResource(
                                R.plurals.max_applied, state.maxApplied, state.maxApplied, (eps * 255).roundToInt(),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    OutlinedButton(onClick = { sheet = InfoMode.START }, enabled = faces > 0) {
                        Text(stringResource(R.string.max_again))
                    }
                }
                else -> Button(onClick = { sheet = InfoMode.START }, enabled = faces > 0 && !state.detecting) {
                    Text(stringResource(R.string.max_start, minutes))
                }
            }
            TextButton(onClick = { sheet = InfoMode.READ }, modifier = Modifier.padding(start = 0.dp)) {
                Icon(Icons.Outlined.Info, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.max_how))
            }
        }
    }

    sheet?.let { mode ->
        InvisibleInfoSheet(
            minutesPerFace = MaxPrivacyWorker.estimateMinutes(1),
            totalMinutes = minutes,
            requireConsent = mode == InfoMode.START,
            onDismiss = { sheet = null },
            onConfirm = {
                sheet = null
                if (mode == InfoMode.START) start()
            },
        )
    }
}

enum class InfoMode { READ, START }

/**
 * The honest explainer: what invisible protection does, what the lab measured, the arms race and
 * the cost. Before Max privacy starts, the user has to tick that it is not a guarantee.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InvisibleInfoSheet(
    minutesPerFace: Int,
    totalMinutes: Int,
    requireConsent: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var understood by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                stringResource(R.string.info_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
            )
            InfoSection(R.string.info_what_title, stringResource(R.string.info_what))
            InfoSection(R.string.info_tests_title, stringResource(R.string.info_tests))
            InfoSection(R.string.info_race_title, stringResource(R.string.info_race))
            InfoSection(R.string.info_cost_title, stringResource(R.string.info_cost, minutesPerFace))
            InfoSection(R.string.info_advice_title, stringResource(R.string.info_advice))

            if (requireConsent) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .toggleable(value = understood, role = Role.Checkbox, onValueChange = { understood = it })
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = understood, onCheckedChange = null)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.info_understand), style = MaterialTheme.typography.bodyLarge)
                }
                Button(onClick = onConfirm, enabled = understood, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(stringResource(R.string.max_start, totalMinutes))
                }
            } else {
                Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(stringResource(R.string.info_ok))
                }
            }
        }
    }
}

@Composable
private fun InfoSection(title: Int, body: String) {
    Column {
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
