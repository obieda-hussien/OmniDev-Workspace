package com.omnidev.workspace.ui.chat

import android.content.*
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import com.omnidev.workspace.ui.motion.LocalOmniMotion
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.omnidev.workspace.data.chatmedia.*
import com.omnidev.workspace.data.model.*
import kotlinx.coroutines.*

/** VoiceInteractionSession has no Activity result registry; its host supplies a real picker bridge. */
internal val LocalChatMediaSaveAs = staticCompositionLocalOf<((AttachmentMeta) -> Unit)?> { null }
internal val LocalChatMediaExternalActivity = staticCompositionLocalOf<((Intent) -> Unit)?> { null }

/** Service contexts need a new task, including the outer sharing chooser. */
internal fun startChatMediaActivity(context: Context, intent: Intent) {
    var host = context
    while (host is ContextWrapper && host !is android.app.Activity) {
        val base = host.baseContext
        if (base === host) break
        host = base
    }
    if (host !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}

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

private data class MediaCardSnapshot(val job: MediaJob? = null, val actual: AttachmentMeta? = null,
    val loaded: Boolean = false, val worker: String? = null, val monitorError: Boolean = false)

@Composable
internal fun ChatMediaCard(original: AttachmentMeta) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var busy by remember(original.uri) { mutableStateOf(false) }
    var viewer by remember(original.uri) { mutableStateOf(false) }
    var menu by remember(original.uri) { mutableStateOf(false) }
    var actionError by remember(original.uri) { mutableStateOf<String?>(null) }
    val isJob = original.uri.startsWith("omni-media-job:")
    val snapshot by produceState(MediaCardSnapshot(actual = if (isJob) null else original, loaded = !isJob), original.uri, lifecycle) {
        if (isJob) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                value = try { withContext(Dispatchers.IO) {
                    val id = original.uri.removePrefix("omni-media-job:")
                    val store = MediaJobStore(context)
                    var job = runCatching { store.get(id) }.getOrNull()
                    var worker: String? = null; var unavailable = false; var workLoaded = false
                    if (job?.state in setOf("queued", "processing", "waiting")) {
                        try {
                            val infos = androidx.work.WorkManager.getInstance(context).getWorkInfosForUniqueWork("omni-media-$id").get(2, java.util.concurrent.TimeUnit.SECONDS)
                            val active = infos.firstOrNull { !it.state.isFinished }
                            if (job?.state == "queued" && active != null) MediaGenerationWorker.promoteQueued(context, job, active, System.currentTimeMillis())
                            worker = (active ?: infos.maxByOrNull { info -> info.tags.firstOrNull { it.startsWith("omni-media-created:") }?.substringAfter(':')?.toLongOrNull() ?: 0L })?.state?.name
                            workLoaded = true
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { unavailable = true }
                        // Immediate user work runs outside WorkManager; its liveness is authoritative.
                        if (MediaGenerationService.isActive(id)) { worker = "RUNNING"; workLoaded = true; unavailable = false }
                        val reconciled = MediaCardStatus.reconcile(job!!, worker, workLoaded, System.currentTimeMillis())
                        if (reconciled != job && store.compareAndUpdate(job, reconciled)) {
                            job = reconciled
                            try { MediaCompletionPublisher.failure(context, reconciled) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { /* The failed card remains visible even if history delivery is unavailable. */ }
                        }
                    }
                    val actual = if (job?.state == "completed") ChatMediaStore.metadata(context, job.path.orEmpty()) else null
                    MediaCardSnapshot(job, actual, true, worker, unavailable)
                } } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    MediaCardSnapshot(job = value.job, actual = value.actual, loaded = true, monitorError = true)
                }
                delay(if (value.job?.state in setOf("completed", "failed", "cancelled")) 4000 else 1200)
            }
        }
    }
    val job = snapshot.job
    val meta = snapshot.actual ?: original
    val status = if (isJob) MediaCardStatus.from(job, snapshot.loaded, snapshot.actual != null, snapshot.worker, snapshot.monitorError)
        else MediaCardStatus(MediaStage.READY, "Ready", "")
    fun action(block: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true; actionError = null
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { actionError = "This action could not finish. Check file access, available storage or your connection and try again." }
            finally { busy = false }
        }
    }
    val externalActivity = LocalChatMediaExternalActivity.current
    fun openExternal(intent: Intent) {
        if (externalActivity != null) externalActivity(intent) else startChatMediaActivity(context, intent)
    }
    val registryOwner = LocalActivityResultRegistryOwner.current
    val saveBridge = LocalChatMediaSaveAs.current
    val saveAs: (() -> Unit)? = if (registryOwner != null) {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(meta.mimeType)) { uri ->
            if (uri != null) action {
                try { ChatMediaStore.export(context, meta, uri); Toast.makeText(context, "File saved", Toast.LENGTH_SHORT).show() }
                catch (error: Exception) { runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }; throw error }
            }
        }
        val launch: () -> Unit = { launcher.launch(ChatMediaStore.safeName(meta.fileName)) }
        launch
    } else saveBridge?.let { bridge -> { bridge(meta) } }
    val motion = LocalOmniMotion.current
    val shape = RoundedCornerShape(24.dp)
    val ratio = job?.aspect?.split(':')?.let { parts -> if (parts.size == 2) parts[0].toFloatOrNull()?.let { a -> parts[1].toFloatOrNull()?.takeIf { it > 0 }?.let { a / it } } else null }
        ?.coerceIn(.8f, 1.8f) ?: if (meta.mediaType == AttachmentMediaType.VIDEO) 16f / 9f else 1.15f
    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f)),
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().testTag("chat-media-card")) {
        Column(if (motion.reduced || motion.compact) Modifier else Modifier.animateContentSize(tween(280))) {
            if (status.stage != MediaStage.READY) {
                MediaGenerationPreview(status, original.mediaType, Modifier.fillMaxWidth().heightIn(max = 340.dp).aspectRatio(ratio))
            } else when (meta.mediaType) {
                AttachmentMediaType.IMAGE -> Box(Modifier.fillMaxWidth().heightIn(max = 340.dp).aspectRatio(ratio).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                    MediaImage(meta, Modifier.fillMaxSize().clickable { viewer = true })
                    IconButton(onClick = { viewer = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).clip(CircleShape).background(Color.Black.copy(alpha = .35f))) {
                        Icon(Icons.Default.Fullscreen, "Expand image", tint = Color.White)
                    }
                }
                AttachmentMediaType.VIDEO -> MediaVideoPreview(meta, Modifier.fillMaxWidth().heightIn(max = 320.dp).aspectRatio(ratio)) { viewer = true }
                AttachmentMediaType.AUDIO -> Column(Modifier.padding(16.dp)) { ChatAudioPlayer(meta) }
                else -> Unit
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(when(meta.mediaType) { AttachmentMediaType.IMAGE -> Icons.Default.Image; AttachmentMediaType.VIDEO -> Icons.Default.Movie; AttachmentMediaType.AUDIO -> Icons.Default.MusicNote; else -> Icons.Default.InsertDriveFile }, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text(meta.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                        Text(if (isJob) job?.model ?: "Saved creation" else meta.mimeType, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Surface(shape = CircleShape, color = if (status.stage == MediaStage.FAILED) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer) {
                        Text(when (status.stage) { MediaStage.READY -> "READY"; MediaStage.FAILED -> "FAILED"; MediaStage.CANCELLED -> "STOPPED"; MediaStage.QUEUED -> "QUEUED"; MediaStage.WAITING -> "WAITING"; else -> "IN PROGRESS" },
                            Modifier.padding(horizontal = 9.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (status.stage != MediaStage.READY) {
                    Text(status.detail, style = MaterialTheme.typography.bodySmall, color = if (status.stage == MediaStage.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("media-status-detail"))
                    status.code?.let { Text("$it" + if ((job?.failures ?: 0) > 0) " · status attempts ${job?.failures}" else "", style = MaterialTheme.typography.labelSmall) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (job != null && MediaQueuePolicy.canStart(job)) FilledTonalButton(onClick = { action { MediaGenerationService.startNow(context, job.id) } }, enabled = !busy) { Text(if (busy) "Starting…" else "Start now") }
                        if (job != null && MediaGenerationFailure.canResume(job)) FilledTonalButton(onClick = { action { MediaGenerationWorker.enqueue(context, job.id) } }, enabled = !busy) { Text("Check existing job") }
                        if (job?.state in setOf("queued", "processing", "waiting")) OutlinedButton(onClick = { action { MediaGenerationWorker.cancel(context, job!!.id) } }, enabled = !busy) { Text("Cancel") }
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(enabled = !busy && (Build.VERSION.SDK_INT >= 29 || saveAs != null), onClick = {
                            if (Build.VERSION.SDK_INT < 29) action { checkNotNull(saveAs).invoke() }
                            else action { ChatMediaStore.save(context, meta); Toast.makeText(context, "Saved to device", Toast.LENGTH_SHORT).show() }
                        }) { Icon(Icons.Default.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Save") }
                        Text(if (meta.sizeBytes > 0) mediaFileSize(meta.sizeBytes) else meta.mimeType.substringAfter('/'), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Box {
                            IconButton(enabled = !busy, onClick = { menu = true }) { Icon(Icons.Default.MoreHoriz, "Media actions") }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text("Share") }, leadingIcon = { Icon(Icons.Default.Share, null) }, onClick = {
                                    menu = false; action {
                                        val uri = ChatMediaStore.shareUri(context, meta)
                                        val send = Intent(Intent.ACTION_SEND).setType(meta.mimeType).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("File", uri) }
                                        openExternal(Intent.createChooser(send, "Share file"))
                                    }
                                })
                                DropdownMenuItem(text = { Text("Open with…") }, leadingIcon = { Icon(Icons.Default.OpenInNew, null) }, onClick = {
                                    menu = false; action {
                                        val uri = ChatMediaStore.shareUri(context, meta)
                                        openExternal(Intent(Intent.ACTION_VIEW).setDataAndType(uri, meta.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("File", uri) })
                                    }
                                })
                                DropdownMenuItem(text = { Text("Save as…") }, enabled = !busy && saveAs != null, leadingIcon = { Icon(Icons.Default.FolderOpen, null) }, onClick = { menu = false; action { checkNotNull(saveAs).invoke() } })
                            }
                        }
                    }
                }
                actionError?.let { error ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(error, Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        IconButton(onClick = { actionError = null }) { Icon(Icons.Default.Close, "Dismiss media error") }
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
    if (viewer && snapshot.actual != null) ChatMediaViewer(meta) { viewer = false }
}

private fun mediaFileSize(bytes: Long): String = if (bytes >= 1024 * 1024) "%.1f MB".format(java.util.Locale.ROOT, bytes / (1024f * 1024f)) else "${(bytes / 1024).coerceAtLeast(1)} KB"

@Composable
private fun MediaVideoPreview(meta: AttachmentMeta, modifier: Modifier, onOpen: () -> Unit) {
    val context = LocalContext.current
    val frame by produceState<android.graphics.Bitmap?>(null, meta.uri) {
        if (!meta.uri.startsWith("https://")) value = withContext(Dispatchers.IO) {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, Uri.parse(meta.uri))
                if (Build.VERSION.SDK_INT >= 27) retriever.getScaledFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 720, 405)
                else null
            } catch (_: Exception) { null } finally { runCatching { retriever.release() } }
        }
    }
    Box(modifier.background(Color(0xFF19162B)).clickable(onClick = onOpen), contentAlignment = Alignment.Center) {
        frame?.let { Image(it.asImageBitmap(), meta.fileName, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        Box(Modifier.size(58.dp).clip(CircleShape).background(Color.Black.copy(alpha = .45f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.PlayArrow, "Play video", Modifier.size(34.dp), tint = Color.White)
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun MediaImage(meta: AttachmentMeta, modifier: Modifier, zoom: Boolean = false) {
    val context = LocalContext.current
    var error by remember(meta.uri) { mutableStateOf<String?>(null) }
    val bitmap by produceState<android.graphics.Bitmap?>(null, meta.uri, zoom) {
        value = null
        try {
            error = null
            value = ChatMediaStore.bitmap(context, meta, if (zoom) 2048 else 1000)
            if (value == null) error = "This image could not be decoded. Open it with another app or save the file."
        }
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
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledIconButton(enabled = !loading, onClick = {
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
            Text(error ?: "${mediaTime(position)} / ${if (prepared) mediaTime(duration) else "—:—"}", style = MaterialTheme.typography.labelSmall)
        }
        IconButton(onClick = { close() }) { Icon(Icons.Default.Stop, "Stop and close audio") }
    }
}

private fun mediaTime(ms: Float): String { val seconds = (ms / 1000).toInt(); return "%d:%02d".format(java.util.Locale.ROOT, seconds / 60, seconds % 60) }
