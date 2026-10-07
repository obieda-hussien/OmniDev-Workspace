package com.omnidev.workspace.ui.chat

import android.content.*
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.omnidev.workspace.data.chatmedia.*
import com.omnidev.workspace.data.model.*
import kotlinx.coroutines.*

/** Only one user-started chat player can own audio; leaving a card/window always stops it. */
private object ChatPlayback {
    private var stop: (() -> Unit)? = null
    fun claim(next: () -> Unit) { val previous = stop; stop = next; if (previous !== next) previous?.invoke() }
    fun release(owned: () -> Unit) { if (stop === owned) stop = null }
}

@Composable
internal fun ChatMessageMedia(message: ChatMessage) {
    val context = LocalContext.current
    val discovered by produceState<List<AttachmentMeta>>(emptyList(), message.content) {
        value = ChatMediaStore.references(context, message.content)
    }
    val files = remember(message.attachments, discovered) {
        (message.attachments + discovered).distinctBy { it.uri }.take(10)
    }
    if (files.isNotEmpty()) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        files.forEach { meta -> key(meta.uri) { ChatMediaCard(meta) } }
    }
}

@Composable
private fun ChatMediaCard(original: AttachmentMeta) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember(original.uri) { mutableStateOf(false) }
    var viewer by remember(original.uri) { mutableStateOf(false) }
    val isJob = original.uri.startsWith("omni-media-job:")
    val job by produceState<MediaJob?>(null, original.uri) {
        if (isJob) while (isActive) {
            value = withContext(Dispatchers.IO) { MediaJobStore(context).get(original.uri.removePrefix("omni-media-job:")) }
            delay(1500)
        }
    }
    val actual by produceState<AttachmentMeta?>(if (isJob) null else original, original, job?.path, job?.state) {
        value = if (isJob && job?.state == "completed") ChatMediaStore.metadata(context, job!!.path.orEmpty()) else if (!isJob) original else null
    }
    val meta = actual ?: original
    fun action(block: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { Toast.makeText(context, error.message ?: "Could not open this file.", Toast.LENGTH_LONG).show() }
            finally { busy = false }
        }
    }
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(meta.mimeType)) { uri ->
        if (uri != null) action {
            try { ChatMediaStore.export(context, meta, uri); Toast.makeText(context, "File saved", Toast.LENGTH_SHORT).show() }
            catch (error: Exception) { runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }; throw error }
        }
    }
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(when(meta.mediaType) {
                    AttachmentMediaType.IMAGE -> Icons.Default.Image
                    AttachmentMediaType.VIDEO -> Icons.Default.Movie
                    AttachmentMediaType.AUDIO -> Icons.Default.AudioFile
                    else -> Icons.Default.InsertDriveFile
                }, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(meta.fileName, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    Text(if (meta.sizeBytes > 0) "${meta.mimeType} · ${meta.sizeBytes / 1024} KB" else meta.mimeType,
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (isJob && actual == null) {
                if (job?.state in setOf(null, "queued", "processing")) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(job?.error ?: "Generating ${if (original.mediaType == AttachmentMediaType.VIDEO) "video" else "image"}… This card updates when ready.", style = MaterialTheme.typography.bodySmall)
                Row {
                    if (job?.operation != null && job?.state == "failed") TextButton(onClick = {
                        MediaGenerationWorker.enqueue(context, job!!.id)
                    }) { Text("Check status") }
                    if (job?.state in setOf("queued", "processing")) TextButton(onClick = { MediaGenerationWorker.cancel(context, job!!.id) }) { Text("Cancel") }
                }
            } else {
                when (meta.mediaType) {
                    AttachmentMediaType.IMAGE -> MediaImage(meta, Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 280.dp)
                        .clip(RoundedCornerShape(12.dp)).clickable { viewer = true })
                    AttachmentMediaType.VIDEO -> FilledTonalButton(onClick = { viewer = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Play video")
                    }
                    AttachmentMediaType.AUDIO -> ChatAudioPlayer(meta)
                    else -> Unit
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(enabled = !busy, onClick = {
                        if (meta.mediaType in setOf(AttachmentMediaType.IMAGE, AttachmentMediaType.VIDEO)) viewer = true
                        else action {
                            val uri = ChatMediaStore.shareUri(context, meta)
                            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, meta.mimeType)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("File", uri) })
                        }
                    }) { Text("Open") }
                    TextButton(enabled = !busy, onClick = {
                        if (Build.VERSION.SDK_INT < 29) saveAs.launch(ChatMediaStore.safeName(meta.fileName))
                        else action { ChatMediaStore.save(context, meta); Toast.makeText(context, "Saved to ${if (meta.mediaType in setOf(AttachmentMediaType.IMAGE, AttachmentMediaType.VIDEO)) "gallery" else "device"}", Toast.LENGTH_SHORT).show() }
                    }) { Text(if (Build.VERSION.SDK_INT < 29) "Save as" else if (meta.mediaType in setOf(AttachmentMediaType.IMAGE, AttachmentMediaType.VIDEO)) "Save to gallery" else "Save") }
                    IconButton(enabled = !busy, onClick = { action {
                        val uri = ChatMediaStore.shareUri(context, meta)
                        val send = Intent(Intent.ACTION_SEND).setType(meta.mimeType).putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("File", uri) }
                        context.startActivity(Intent.createChooser(send, "Share file"))
                    } }) { Icon(Icons.Default.Share, "Share ${meta.fileName}") }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
    if (viewer && actual != null) ChatMediaViewer(meta) { viewer = false }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun MediaImage(meta: AttachmentMeta, modifier: Modifier, zoom: Boolean = false) {
    val context = LocalContext.current
    var error by remember(meta.uri) { mutableStateOf<String?>(null) }
    val bitmap by produceState<android.graphics.Bitmap?>(null, meta.uri, zoom) {
        value = null
        try { value = ChatMediaStore.bitmap(context, meta, if (zoom) 2048 else 1000) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Preview unavailable. Open or save the file to view it." }
    }
    var scale by remember(meta.uri) { mutableFloatStateOf(1f) }
    var offset by remember(meta.uri) { mutableStateOf(Offset.Zero) }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            val image = remember(bitmap) { bitmap!!.asImageBitmap() }
            Image(image, meta.fileName, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()
                .then(if (zoom) Modifier.pointerInput(meta.uri) {
                    detectTransformGestures { _, pan, factor, _ ->
                        scale = (scale * factor).coerceIn(1f, 6f)
                        offset = if (scale == 1f) Offset.Zero else Offset((offset.x + pan.x).coerceIn(-4000f, 4000f), (offset.y + pan.y).coerceIn(-4000f, 4000f))
                    }
                }.pointerInput(meta.uri) {
                    detectTapGestures(onDoubleTap = { scale = if (scale > 1f) 1f else 2.5f; offset = Offset.Zero })
                }.graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y } else Modifier))
        } else if (error == null) CircularProgressIndicator(Modifier.size(28.dp))
        else Text(error!!, Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun ChatMediaViewer(meta: AttachmentMeta, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(meta.fileName, Modifier.weight(1f).padding(16.dp), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close viewer", tint = Color.White) }
            }
            if (meta.mediaType == AttachmentMediaType.IMAGE) {
                MediaImage(meta, Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(0.dp)), zoom = true)
                Text("Pinch to zoom · double tap to reset", Modifier.align(Alignment.CenterHorizontally).padding(12.dp), color = Color.White)
            } else ChatVideoPlayer(meta, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable
private fun ChatVideoPlayer(meta: AttachmentMeta, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var view by remember(meta.uri) { mutableStateOf<VideoView?>(null) }
    var error by remember(meta.uri) { mutableStateOf<String?>(null) }
    val uri by produceState<Uri?>(null, meta.uri) {
        value = null
        try { value = Uri.parse(ChatMediaStore.materialize(context, meta).uri) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Video unavailable. Try opening it with another player." }
    }
    val pause = remember(meta.uri) { { view?.pause(); Unit } }
    DisposableEffect(lifecycle, meta.uri) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); pause(); view?.stopPlayback(); ChatPlayback.release(pause); view = null }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (uri != null) AndroidView(factory = {
            VideoView(it).apply {
                view = this
                val video = this
                setMediaController(MediaController(it).apply { setAnchorView(video) })
                setOnPreparedListener { ChatPlayback.claim(pause); start() }
                setOnErrorListener { _, _, _ -> error = "This video format is not supported by the device. Open it with another player."; true }
                setVideoURI(uri)
            }
        }, modifier = Modifier.fillMaxSize())
        if (error != null) Text(error!!, Modifier.padding(16.dp), color = Color.White)
        else if (uri == null) CircularProgressIndicator()
    }
}

@Composable
private fun ChatAudioPlayer(meta: AttachmentMeta) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var player by remember(meta.uri) { mutableStateOf<MediaPlayer?>(null) }
    var prepared by remember(meta.uri) { mutableStateOf(false) }
    var playing by remember(meta.uri) { mutableStateOf(false) }
    var loading by remember(meta.uri) { mutableStateOf(false) }
    var position by remember(meta.uri) { mutableFloatStateOf(0f) }
    var duration by remember(meta.uri) { mutableFloatStateOf(1f) }
    var error by remember(meta.uri) { mutableStateOf<String?>(null) }
    var disposed by remember(meta.uri) { mutableStateOf(false) }
    val stop = remember(meta.uri) { { runCatching { if (prepared) player?.pause() }; playing = false; Unit } }
    fun close() { stop(); player?.release(); player = null; prepared = false; position = 0f; loading = false; ChatPlayback.release(stop) }
    DisposableEffect(lifecycle, meta.uri) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) stop() }
        lifecycle.addObserver(observer)
        onDispose { disposed = true; lifecycle.removeObserver(observer); close() }
    }
    LaunchedEffect(playing, player) {
        while (playing && isActive) {
            runCatching { position = player?.currentPosition?.toFloat() ?: 0f }; delay(300)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(enabled = !loading, onClick = {
            if (prepared) {
                if (playing) stop() else { ChatPlayback.claim(stop); player?.start(); playing = true }
            } else scope.launch {
                loading = true; error = null
                try {
                    val uri = Uri.parse(ChatMediaStore.materialize(context, meta).uri)
                    if (disposed) return@launch
                    player = MediaPlayer()
                    player!!.apply {
                        setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).setUsage(AudioAttributes.USAGE_MEDIA).build())
                        setDataSource(context, uri)
                        setOnPreparedListener { current ->
                            if (disposed || lifecycle.currentState < Lifecycle.State.STARTED) { close(); return@setOnPreparedListener }
                            prepared = true; loading = false; duration = current.duration.toFloat().coerceAtLeast(1f)
                            ChatPlayback.claim(stop); current.start(); playing = true
                        }
                        setOnCompletionListener { playing = false; position = duration }
                        setOnErrorListener { _, _, _ -> error = "Audio format unavailable. Try opening with another player."; close(); true }
                        prepareAsync()
                    }
                } catch (cancelled: CancellationException) { close(); throw cancelled }
                catch (_: Exception) { error = "Could not play this audio file."; close() }
            }
        }) { if (loading) CircularProgressIndicator(Modifier.size(20.dp)) else Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pause audio" else "Play audio") }
        Column(Modifier.weight(1f)) {
            Slider(value = position.coerceIn(0f, duration), onValueChange = { position = it; if (prepared) player?.seekTo(it.toInt()) },
                valueRange = 0f..duration, enabled = prepared)
            Text(error ?: "${(position / 1000).toInt()}s / ${(duration / 1000).toInt()}s", style = MaterialTheme.typography.labelSmall)
        }
        IconButton(onClick = { close() }) { Icon(Icons.Default.Stop, "Stop and close audio") }
    }
}
