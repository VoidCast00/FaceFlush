package io.github.voidcast00.faceflush.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.voidcast00.faceflush.CleanState
import io.github.voidcast00.faceflush.EditorEvent
import io.github.voidcast00.faceflush.R
import io.github.voidcast00.faceflush.effects.EditorMode
import io.github.voidcast00.faceflush.ui.theme.SectionLabel
import kotlinx.coroutines.flow.Flow

@Composable
fun HomeScreen(
    onPickPhoto: (EditorMode) -> Unit,
    onCleanMetadata: () -> Unit,
    cleanState: CleanState,
    onCleanDone: () -> Unit,
    events: Flow<EditorEvent>,
) {
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(events, snackbar)
    val colors = MaterialTheme.colorScheme

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = colors.background) { padding ->
        Box(Modifier.fillMaxSize()) {
            AsciiArt(
                color = colors.onBackground.copy(alpha = 0.22f),
                accent = colors.primary.copy(alpha = 0.75f),
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(top = 8.dp),
            )
            BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .heightIn(min = maxHeight)
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Text(
                        stringResource(R.string.home_headline),
                        style = MaterialTheme.typography.displayMedium,
                        color = colors.onBackground,
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.home_tagline),
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(28.dp))
                    ModeCard(
                        Icons.Outlined.GridOn, R.string.mode_visible_title, R.string.mode_visible_body,
                        highlighted = true,
                    ) { onPickPhoto(EditorMode.VISIBLE) }
                    Spacer(Modifier.height(12.dp))
                    ModeCard(
                        Icons.Outlined.Shield, R.string.mode_invisible_title, R.string.mode_invisible_body,
                        highlighted = false,
                    ) { onPickPhoto(EditorMode.INVISIBLE) }
                    Spacer(Modifier.height(12.dp))
                    ModeCard(
                        Icons.Outlined.CleaningServices, R.string.mode_clean_title, R.string.mode_clean_body,
                        highlighted = false,
                        onClick = onCleanMetadata,
                    )
                    Spacer(Modifier.height(28.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        listOf(R.string.badge_offline, R.string.badge_no_metadata, R.string.badge_on_device).forEach {
                            Text(stringResource(it), style = SectionLabel, color = colors.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
    CleanSheet(cleanState, onCleanDone)
}

/** One of the two ways into the editor; tapping it opens the photo picker. */
@Composable
private fun ModeCard(icon: ImageVector, title: Int, body: Int, highlighted: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = if (highlighted) colors.secondaryContainer else colors.surfaceContainer,
        border = if (highlighted) null else BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (highlighted) colors.secondary else colors.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = if (highlighted) colors.onSecondary else colors.primary, modifier = Modifier.size(24.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(
                    stringResource(title),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (highlighted) colors.onSecondaryContainer else colors.onSurface,
                )
                Text(
                    stringResource(body),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (highlighted) colors.onSecondaryContainer.copy(alpha = 0.8f) else colors.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Outlined.ArrowForward, null,
                tint = if (highlighted) colors.onSecondaryContainer else colors.onSurfaceVariant,
            )
        }
    }
}
