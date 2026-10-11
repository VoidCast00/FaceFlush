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
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.voidcast00.faceflush.EditorEvent
import io.github.voidcast00.faceflush.R
import io.github.voidcast00.faceflush.ui.theme.SectionLabel
import kotlinx.coroutines.flow.Flow

@Composable
fun HomeScreen(onPickPhoto: () -> Unit, events: Flow<EditorEvent>) {
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(events, snackbar)
    val colors = MaterialTheme.colorScheme

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = colors.background) { padding ->
        Box(Modifier.fillMaxSize()) {
            AsciiArt(
                R.raw.ascii_portrait,
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
                    Button(
                        onClick = onPickPhoto,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.secondaryContainer,
                            contentColor = colors.onSecondaryContainer,
                        ),
                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) {
                        Icon(Icons.Outlined.Image, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.pick_photo), style = MaterialTheme.typography.titleMedium)
                    }
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
}
