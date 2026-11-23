package dev.hamza.explorer.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.AudioManager
import android.media.ExifInterface
import android.media.MediaPlayer
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import dev.hamza.explorer.R
import dev.hamza.explorer.core.FileOps
import dev.hamza.explorer.core.TextPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
private fun CenteredMessage(text: String, color: Color = Muted) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = color, textAlign = TextAlign.Center, style = MaterialTheme.typography.body2)
    }
}

// ---------------- Images: pinch or crown to zoom, drag to pan, double-tap to reset ----------------

private sealed interface Decoded {
    data object Loading : Decoded
    data class Ok(val bitmap: Bitmap) : Decoded
    data class Error(val message: String) : Decoded
}

private fun decodeImage(file: File, maxSide: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    val rotation = runCatching {
        when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    }.getOrDefault(0f)
    if (rotation == 0f) return bmp
    return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation) }, true)
}

@Composable
fun ImageViewer(vm: ExplorerViewModel) {
    val file = vm.viewing ?: return
    val image by produceState<Decoded>(Decoded.Loading, file) {
        value = withContext(Dispatchers.IO) {
            try {
                // Twice the screen size leaves room to zoom without running out of memory.
                decodeImage(file, 1200)?.let { Decoded.Ok(it) } ?: Decoded.Error("Can't show this image.")
            } catch (_: OutOfMemoryError) {
                Decoded.Error("This image is too big for the watch.")
            }
        }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .crownTicks { d ->
                scale = (if (d > 0) scale * 1.2f else scale / 1.2f).coerceIn(1f, 8f)
                if (scale == 1f) offset = Offset.Zero
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                })
            }
            .pointerInput(Unit) {
                // Only takes over drags when zoomed in or pinching, so swipe-right still goes back.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pinching = event.changes.count { it.pressed } > 1
                        if (pinching || scale > 1f) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 8f)
                            offset = if (scale == 1f) Offset.Zero else offset + event.calculatePan()
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when (val img = image) {
            Decoded.Loading -> CircularProgressIndicator(Modifier.size(36.dp))
            is Decoded.Error -> CenteredMessage(img.message, ErrorColor)
            is Decoded.Ok -> Image(
                bitmap = img.bitmap.asImageBitmap(),
                contentDescription = file.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale; scaleY = scale
                    translationX = offset.x; translationY = offset.y
                },
            )
        }
    }
}

// ---------------- Text: scrolls with the crown ----------------

@Composable
fun TextViewer(vm: ExplorerViewModel) {
    val file = vm.viewing ?: return
    val preview by produceState<Result<TextPreview>?>(null, file) {
        value = withContext(Dispatchers.IO) { runCatching { FileOps.readText(file) } }
    }
    // Split into chunks so the list stays smooth and the crown scrolls through it.
    val chunks = remember(preview) {
        preview?.getOrNull()?.text?.let { chunkText(it) }.orEmpty()
    }
    ListScreen {
        header(file.name)
        val p = preview
        when {
            p == null -> item { CircularProgressIndicator(Modifier.size(36.dp)) }
            p.isFailure -> note(p.exceptionOrNull()?.message ?: "Can't read this file.", ErrorColor)
            else -> {
                val t = p.getOrThrow()
                if (t.looksBinary) note("This doesn't look like a text file.", ErrorColor)
                if (t.text.isEmpty()) note("The file is empty.")
                chunks.forEach { c ->
                    item {
                        Text(
                            c,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                        )
                    }
                }
                if (t.truncated) note("Showing the first ${formatSize(t.text.toByteArray().size.toLong())}.")
            }
        }
    }
}

private fun chunkText(text: String): List<String> {
    val out = mutableListOf<String>()
    val sb = StringBuilder()
    for (line in text.lineSequence()) {
        var l = line
        while (l.length > 400) { out += l.substring(0, 400); l = l.substring(400) }
        if (sb.length + l.length > 400 && sb.isNotEmpty()) { out += sb.toString().trimEnd('\n'); sb.clear() }
        sb.append(l).append('\n')
    }
    if (sb.isNotBlank()) out += sb.toString().trimEnd('\n')
    return out
}

// ---------------- Audio: play/pause, skip 10 s, crown changes volume ----------------

@Composable
fun AudioPlayer(vm: ExplorerViewModel) {
    val file = vm.viewing ?: return
    val context = LocalContext.current
    KeepScreenOn()
    var error by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableIntStateOf(0) }
    var duration by remember { mutableIntStateOf(0) }
    val player = remember(file) {
        try {
            MediaPlayer().apply {
                setDataSource(file.path)
                prepare()
                setOnCompletionListener { playing = false }
            }
        } catch (e: Exception) {
            error = "Can't play this file."
            null
        }
    }
    DisposableEffect(player) {
        player?.let { duration = it.duration; it.start(); playing = true }
        onDispose { player?.release() }
    }
    LaunchedEffect(player) {
        while (player != null) {
            runCatching { position = player.currentPosition }
            delay(500)
        }
    }
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    Box(
        Modifier
            .fillMaxSize()
            .crownTicks { d ->
                audio.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    if (d > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                    AudioManager.FLAG_SHOW_UI,
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            progress = if (duration > 0) position.toFloat() / duration else 0f,
            modifier = Modifier.fillMaxSize().padding(4.dp),
            strokeWidth = 5.dp,
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 30.dp),
        ) {
            Text(file.nameWithoutExtension, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, style = MaterialTheme.typography.title3)
            Text(
                error ?: "${formatTime(position)} / ${formatTime(duration)}",
                color = if (error != null) ErrorColor else Muted,
                style = MaterialTheme.typography.caption2,
            )
            if (player != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    SmallIconButton(R.drawable.ic_rewind, "Back 10 seconds") {
                        player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)); position = player.currentPosition
                    }
                    Box(Modifier.width(6.dp))
                    Button(
                        onClick = {
                            if (player.isPlaying) { player.pause(); playing = false } else { player.start(); playing = true }
                        },
                        modifier = Modifier.size(ButtonDefaults.DefaultButtonSize),
                    ) {
                        Icon(painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play), contentDescription = if (playing) "Pause" else "Play")
                    }
                    Box(Modifier.width(6.dp))
                    SmallIconButton(R.drawable.ic_forward, "Forward 10 seconds") {
                        player.seekTo((player.currentPosition + 10_000).coerceAtMost(player.duration)); position = player.currentPosition
                    }
                }
                Text("Turn the crown for volume", color = Muted, style = MaterialTheme.typography.caption3, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun SmallIconButton(icon: Int, label: String, onClick: () -> Unit) {
    Button(onClick = onClick, colors = ButtonDefaults.secondaryButtonColors(), modifier = Modifier.size(ButtonDefaults.SmallButtonSize)) {
        Icon(painterResource(icon), contentDescription = label)
    }
}

// ---------------- Video: tap to pause or play ----------------

@Composable
fun VideoPlayer(vm: ExplorerViewModel) {
    val file = vm.viewing ?: return
    KeepScreenOn()
    var error by remember { mutableStateOf<String?>(null) }
    var view by remember { mutableStateOf<VideoView?>(null) }
    DisposableEffect(Unit) { onDispose { view?.stopPlayback() } }
    Box(
        Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
            detectTapGestures { view?.let { if (it.isPlaying) it.pause() else it.start() } }
        },
        contentAlignment = Alignment.Center,
    ) {
        if (error != null) {
            CenteredMessage(error!!, ErrorColor)
        } else {
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        setOnErrorListener { _, _, _ -> error = "Can't play this video."; true }
                        setOnPreparedListener { start() }
                        setVideoPath(file.path)
                        view = this
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

// ---------------- Web pages: shown in the app with the system WebView ----------------

/**
 * Sends one smooth wheel scroll to the page at its centre. Chromium routes wheel events to the
 * element under the pointer, so this also scrolls pages whose content sits in an inner scrolling
 * box (where WebView.scrollBy does nothing).
 */
private fun wheel(view: android.webkit.WebView, pixels: Float) {
    val factor = android.view.ViewConfiguration.get(view.context).scaledVerticalScrollFactor
    val props = android.view.MotionEvent.PointerProperties().apply { id = 0; toolType = android.view.MotionEvent.TOOL_TYPE_MOUSE }
    val coords = android.view.MotionEvent.PointerCoords().apply {
        x = view.width / 2f
        y = view.height / 2f
        setAxisValue(android.view.MotionEvent.AXIS_VSCROLL, -pixels / factor)
    }
    val now = android.os.SystemClock.uptimeMillis()
    val ev = android.view.MotionEvent.obtain(
        now, now, android.view.MotionEvent.ACTION_SCROLL, 1, arrayOf(props), arrayOf(coords),
        0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_MOUSE, 0,
    )
    view.dispatchGenericMotionEvent(ev)
    ev.recycle()
}

@android.annotation.SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewer(vm: ExplorerViewModel, onClose: () -> Unit, onOpenAsText: () -> Unit, onOpenWith: () -> Unit) {
    val file = vm.viewing ?: return
    val stepPx = with(androidx.compose.ui.platform.LocalDensity.current) { CrownStep.toPx() }
    var web by remember { mutableStateOf<android.webkit.WebView?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var crashed by remember { mutableStateOf(false) }
    // Create the WebView one frame later, so the spinner is on screen while Chromium starts up
    // (that start-up blocks the main thread for a few seconds on the watch).
    var create by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { withFrameNanos { }; delay(50); create = true }

    // Same glide as the lists: each crown click adds a step, and the page eases toward it.
    var pending by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(web) {
        val w = web ?: return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            val p = pending
            if (kotlin.math.abs(p) > 0.5f) {
                val d = if (kotlin.math.abs(p) < 2f) p else p * 0.28f
                wheel(w, d)
                pending = p - d
            }
        }
    }
    DisposableEffect(Unit) { onDispose { web?.destroy() } }
    KeepScreenOn()

    Box(
        Modifier.fillMaxSize().background(Color.Black).crownTicks { d -> pending += d * stepPx },
        contentAlignment = Alignment.Center,
    ) {
        when {
            crashed -> ListScreen {
                header("Page too heavy")
                note("The watch's web viewer ran out of memory on this page.", ErrorColor)
                item { ActionChip("Open as text", R.drawable.ic_text, colors = androidx.wear.compose.material.ChipDefaults.primaryChipColors(), onClick = onOpenAsText) }
                item { ActionChip("Open with another app", R.drawable.ic_open, onClick = onOpenWith) }
                item { ActionChip("Back", R.drawable.ic_close, onClick = onClose) }
            }
            error != null -> CenteredMessage(error!!, ErrorColor)
            create -> AndroidView(
                factory = { ctx ->
                    try {
                        android.webkit.WebView(ctx).apply {
                            setBackgroundColor(android.graphics.Color.BLACK)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true // many single-file apps save progress here
                            settings.allowFileAccess = true // local pages and their images
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            webViewClient = object : android.webkit.WebViewClient() {
                                override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                                    loading = false
                                    // Pages a little wider than the watch would scroll sideways; clip them instead.
                                    view?.evaluateJavascript(
                                        "(function(){var s=document.createElement('style');" +
                                            "s.textContent='html,body{overflow-x:hidden!important;max-width:100%!important}';" +
                                            "(document.head||document.documentElement).appendChild(s);})()",
                                        null,
                                    )
                                }

                                // The watch's WebView can run out of memory on big pages and its
                                // renderer dies. Without this the whole app is killed with it.
                                override fun onRenderProcessGone(
                                    view: android.webkit.WebView?,
                                    detail: android.webkit.RenderProcessGoneDetail?,
                                ): Boolean {
                                    (view?.parent as? android.view.ViewGroup)?.removeView(view)
                                    view?.destroy()
                                    if (web === view) web = null
                                    loading = false
                                    crashed = true
                                    return true
                                }
                            }
                            loadUrl(android.net.Uri.fromFile(file).toString())
                            web = this
                        }
                    } catch (e: Exception) {
                        error = "This watch can't show web pages. Long-press the file and use Open with another app."
                        android.view.View(ctx)
                    }
                },
                // A small inset only: bigger ones wasted too much of the round screen on the watch.
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 14.dp),
            )
        }
        if (loading && error == null && !crashed) CircularProgressIndicator(Modifier.size(36.dp))
        // Swipe-to-go-back is off here so the page gets every swipe; this button leaves instead.
        if (!crashed) Box(Modifier.fillMaxSize().padding(top = 2.dp), contentAlignment = Alignment.TopCenter) {
            Button(
                onClick = onClose,
                colors = ButtonDefaults.secondaryButtonColors(),
                modifier = Modifier.size(26.dp),
            ) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = "Close", modifier = Modifier.size(16.dp))
            }
        }
    }
}
