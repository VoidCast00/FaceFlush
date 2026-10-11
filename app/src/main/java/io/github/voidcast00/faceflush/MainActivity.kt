package io.github.voidcast00.faceflush

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.voidcast00.faceflush.ui.EditorScreen
import io.github.voidcast00.faceflush.ui.HomeScreen
import io.github.voidcast00.faceflush.ui.theme.FaceFlushTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FaceFlushTheme {
                FaceFlushApp(onShare = ::share)
            }
        }
    }

    private fun share(event: EditorEvent.Share) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = event.mimeType
            putExtra(Intent.EXTRA_STREAM, event.uri)
            clipData = ClipData.newRawUri(null, event.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share_title)))
    }
}

@Composable
private fun FaceFlushApp(onShare: (EditorEvent.Share) -> Unit, vm: EditorViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(vm::open)
    }
    val pick = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    if (state.hasImage) {
        BackHandler(onBack = vm::close)
        EditorScreen(state = state, vm = vm, onShare = onShare)
    } else {
        HomeScreen(onPickPhoto = pick, events = vm.events)
    }
}
