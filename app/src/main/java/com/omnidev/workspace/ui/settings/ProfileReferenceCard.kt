package com.omnidev.workspace.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.omnidev.workspace.data.chatmedia.ProfilePhotoSlot
import com.omnidev.workspace.data.chatmedia.ProfileReferenceStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun ProfileReferenceCard() {
    val context = LocalContext.current
    val store = remember(context) { ProfileReferenceStore(context) }
    val scope = rememberCoroutineScope()
    var references by remember { mutableStateOf<com.omnidev.workspace.data.chatmedia.ProfileReferences?>(null) }
    var selectedSlot by rememberSaveable { mutableStateOf(ProfilePhotoSlot.FACE) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(store) { references = withContext(Dispatchers.IO) { store.get() } }
    fun change(action: suspend () -> Unit) {
        busy = true; error = null
        scope.launch {
            try { action(); references = withContext(Dispatchers.IO) { store.get() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Could not update reference photos. Please try again." }
            finally { busy = false }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        pickerOpen = false
        if (uri != null) change { store.import(selectedSlot, uri) }
    }
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Reference photos", style = MaterialTheme.typography.titleMedium)
            Text("Help Omni create images that look more like you. Photos save immediately and stay on this device until used for a generation request.",
                style = MaterialTheme.typography.bodyMedium)
            for (slot in ProfilePhotoSlot.entries) {
                val file = references?.photo(slot)
                val preview by produceState<android.graphics.Bitmap?>(null, store, file) {
                    value = if (file == null) null else store.preview(slot)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    preview?.let { Image(it.asImageBitmap(), "${slot.label} reference preview",
                        Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Fit) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(slot.label, style = MaterialTheme.typography.titleSmall)
                        Text(slot.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                selectedSlot = slot; pickerOpen = true; picker.launch("image/*")
                            }, enabled = references != null && !busy && !pickerOpen) { Text(if (file == null) "Add photo" else "Replace") }
                            if (file != null) TextButton(onClick = { change { store.remove(slot) } }, enabled = !busy && !pickerOpen) { Text("Remove") }
                        }
                    }
                }
            }
            HorizontalDivider()
            Row {
                Column(Modifier.weight(1f)) {
                    Text("Use for images of me", style = MaterialTheme.typography.titleSmall)
                    Text("When requested, send these photos to your selected Gemini image or OpenAI GPT Image provider. Turn off to stop queued requests from using them.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = references?.allowed == true, onCheckedChange = { change { store.allow(it) } },
                    enabled = references?.files()?.isNotEmpty() == true && !busy && !pickerOpen)
            }
            Text("Try: “Create a professional portrait of me using my reference photos” or “Make a full-body image of me in a new outfit.” Results depend on the model; this does not train it. Use your own photos.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (busy || references == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
