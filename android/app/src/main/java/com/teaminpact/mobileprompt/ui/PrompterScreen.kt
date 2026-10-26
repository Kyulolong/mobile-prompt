package com.teaminpact.mobileprompt.ui

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.Surface
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.teaminpact.mobileprompt.R
import com.teaminpact.mobileprompt.capture.CaptureController
import com.teaminpact.mobileprompt.engine.DisplayToken
import com.teaminpact.mobileprompt.model.AppSettings
import com.teaminpact.mobileprompt.speech.SpeechEngine
import java.util.Locale

/**
 * Full-screen prompter: selfie camera behind, translucent scrim, the script
 * scrolling on top, and record/mode controls. Port of the iOS PrompterView.
 */
@Composable
fun PrompterScreen(script: String, settings: AppSettings, returnUrl: String? = null, onClose: () -> Unit) {
    val context = LocalContext.current
    // One ViewModel per prompter session, cleared as soon as this screen leaves
    // composition — X, back, a new handoff replacing it, or the activity being
    // recreated (app-language change) — so the camera and mic are released and
    // the next script never inherits this one's state.
    val session = remember { PrompterSession() }
    DisposableEffect(session) { onDispose { session.viewModelStore.clear() } }
    val vm = viewModel(session) { PrompterViewModel(context.applicationContext as Application) }
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var showSettings by remember { mutableStateOf(false) }

    fun granted(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    // Permissions, then start. Camera is optional (script-only fallback), mic
    // is required for voice mode.
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        vm.start(script, settings, micGranted = result[Manifest.permission.RECORD_AUDIO] == true || granted(Manifest.permission.RECORD_AUDIO))
    }
    LaunchedEffect(Unit) {
        if (granted(Manifest.permission.CAMERA) && granted(Manifest.permission.RECORD_AUDIO)) {
            vm.start(script, settings, micGranted = true)
        } else {
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
    }

    // Mic can't be used from the background; pause recognition while away,
    // and re-check permission on return (the user may have fixed it in Settings).
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> vm.onBackground()
                Lifecycle.Event.ON_START -> {
                    if (granted(Manifest.permission.RECORD_AUDIO)) vm.micPermissionGranted()
                    vm.onForeground()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Keep the screen awake and hide the status bar while prompting.
    DisposableEffect(view) {
        view.keepScreenOn = true
        val window = (context as? android.app.Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.hide(WindowInsetsCompat.Type.statusBars())
        onDispose {
            view.keepScreenOn = false
            controller?.show(WindowInsetsCompat.Type.statusBars())
        }
    }

    // Settings that apply live.
    SideEffect {
        vm.scroll.autoDpPerSec = settings.autoSpeed.toFloat()
        vm.scroll.readingLineFrac = settings.readingLineFrac.toFloat()
    }
    LaunchedEffect(settings.highlightStyle) { vm.setHighlightStyle(settings.highlightStyle) }
    var lastVoice by remember { mutableStateOf(settings.voiceMode) }
    LaunchedEffect(settings.voiceMode) {
        if (settings.voiceMode != lastVoice) {
            lastVoice = settings.voiceMode
            vm.setMode(settings.voiceMode, settings)
        }
    }

    fun close() {
        vm.stopAll()
        onClose()
    }
    // An edge swipe is easy to make by accident; don't let it end a take
    // unasked. (X is a deliberate tap and closes right away, as on iOS.)
    var confirmClose by remember { mutableStateOf(false) }
    BackHandler { if (vm.capture.isRecording) confirmClose = true else close() }
    LaunchedEffect(vm.capture.isRecording) { if (!vm.capture.isRecording) confirmClose = false }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Camera
        when (vm.capture.cameraStatus) {
            CaptureController.CameraStatus.UNAVAILABLE -> Column(
                Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Filled.VideocamOff, contentDescription = null, Modifier.size(36.dp), tint = Color.White.copy(alpha = 0.25f))
                Text(stringResource(R.string.camera_unavailable), color = Color.White.copy(alpha = 0.25f), fontSize = 12.sp, textAlign = TextAlign.Center)
            }
            else -> {
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            vm.capture.bind(lifecycleOwner, this)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                if (vm.capture.cameraStatus == CaptureController.CameraStatus.STARTING) {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.White.copy(alpha = 0.35f))
                        Spacer(Modifier.height(10.dp))
                        Text(stringResource(R.string.camera_starting), color = Color.White.copy(alpha = 0.35f), fontSize = 12.sp)
                    }
                }
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = settings.scrimOpacity.toFloat())))

        ScriptBox(vm, settings)

        Column(Modifier.fillMaxSize().safeDrawingPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TopBar(vm, settings, onClose = { close() }, onSettings = { showSettings = true })
            AnimatedVisibility(visible = vm.capture.isRecording) { RecordingBadge(vm.capture.recordingSeconds) }
            Spacer(Modifier.weight(1f))
            BottomBar(vm, settings)
        }

        vm.countdown?.let { c ->
            Text(
                "$c",
                Modifier.align(Alignment.Center),
                color = Color.White, fontSize = 130.sp, fontWeight = FontWeight.Black,
            )
        }

        if (settings.voiceMode && vm.permissionState == PrompterViewModel.PermissionState.DENIED) {
            PermissionOverlay(
                Modifier.align(Alignment.Center),
                onOpenSettings = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                },
                onAutoScroll = { settings.voiceMode = false },
            )
        }
        vm.errorMessage?.let { msg ->
            Text(
                msg, Modifier.align(Alignment.Center).padding(24.dp),
                color = Color.White, textAlign = TextAlign.Center,
            )
        }
    }

    if (showSettings) {
        SettingsSheet(settings = settings, compact = true, onDismiss = { showSettings = false })
    }

    if (confirmClose) {
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text(stringResource(R.string.close_while_recording_title)) },
            text = { Text(stringResource(R.string.close_while_recording_body)) },
            confirmButton = {
                TextButton(onClick = { confirmClose = false; close() }) { Text(stringResource(R.string.stop_and_close)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClose = false }) { Text(stringResource(R.string.keep_recording)) }
            },
        )
    }

    // Saved: offer the movie + subtitles to other apps (Perplz registers as a
    // share target) and, for handed-off scripts, the way back to Perplz.
    vm.capture.saveMessage?.let { msg ->
        val shareIntent = vm.recordingShareIntent()
        AlertDialog(
            onDismissRequest = { vm.capture.saveMessage = null },
            title = { Text(stringResource(R.string.notice)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(msg)
                    Spacer(Modifier.height(6.dp))
                    if (shareIntent != null) {
                        TextButton(onClick = {
                            vm.capture.saveMessage = null
                            context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.share_video_srt)))
                        }) { Text(stringResource(R.string.share_video_srt)) }
                    }
                    if (returnUrl != null) {
                        TextButton(onClick = {
                            vm.capture.saveMessage = null
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(returnUrl)))
                            } catch (_: android.content.ActivityNotFoundException) {
                                // Perplz not installed any more: nothing to go back to.
                            }
                        }) { Text(stringResource(R.string.back_to_perplz)) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.capture.saveMessage = null }) { Text(stringResource(R.string.ok)) }
            },
        )
    }
}

/** Owns the per-session ViewModel; see PrompterScreen. */
private class PrompterSession : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}

/**
 * The script itself. In landscape the front camera sits on one side edge; a
 * full-width column makes the reader's gaze wander visibly off-lens, so the
 * script narrows to 55% and hugs the camera's side.
 */
@Composable
private fun ScriptBox(vm: PrompterViewModel, settings: AppSettings) {
    val density = LocalDensity.current.density
    val configuration = LocalConfiguration.current
    val rotation = LocalView.current.display?.rotation ?: Surface.ROTATION_0
    val built = remember { BuiltState() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val boxWidth = if (landscape) maxWidth * 0.55f else maxWidth * settings.portraitWidthFrac.toFloat()
        val topPad = if (landscape) 0.dp else maxHeight * settings.portraitTopFrac.toFloat()
        val bottomPad = if (landscape) 0.dp else maxHeight * settings.portraitBottomFrac.toFloat()
        val bandHeight = maxHeight - topPad - bottomPad
        // ROTATION_90: the phone's top edge (camera) is on the left.
        val cameraOnLeft = rotation == Surface.ROTATION_90
        @Suppress("UNUSED_VARIABLE") val relayoutKey = configuration.orientation

        Row(Modifier.fillMaxSize()) {
            if (!landscape || !cameraOnLeft) Spacer(Modifier.weight(1f, fill = true))
            AndroidView(
                factory = { ctx ->
                    PromptTextView(ctx).apply {
                        onLayoutChange = { vm.attach(this, tokenOffsets(), density) }
                        onTap = { vm.tapped(it) }
                    }
                },
                update = { tv ->
                    val fontPx = (settings.fontSize * density).toFloat()
                    if (built.tokens !== vm.tokens || built.fontPx != fontPx) {
                        built.tokens = vm.tokens
                        built.fontPx = fontPx
                        tv.setContent(vm.tokens, fontPx, if (vm.scriptLang == com.teaminpact.mobileprompt.engine.EngineLang.KO) Locale.KOREAN else Locale.ENGLISH)
                    }
                    tv.setReadingLineFrac(settings.readingLineFrac.toFloat())
                },
                modifier = Modifier
                    .width(boxWidth)
                    .fillMaxSize()
                    .padding(top = topPad, bottom = bottomPad)
                    .graphicsLayer(scaleX = if (settings.mirrored) -1f else 1f),
            )
            if (!landscape || cameraOnLeft) Spacer(Modifier.weight(1f, fill = true))
        }

        // Reading-line marker, aligned with the (possibly inset) box.
        Box(
            Modifier
                .offset(y = topPad + bandHeight * settings.readingLineFrac.toFloat())
                .fillMaxWidth()
                .height(1.dp)
                .background(Color.White.copy(alpha = 0.18f)),
        )
    }
}

private class BuiltState {
    var tokens: List<DisplayToken>? = null
    var fontPx = 0f
}

@Composable
private fun TopBar(vm: PrompterViewModel, settings: AppSettings, onClose: () -> Unit, onSettings: () -> Unit) {
    val (statusColor, statusText) = status(vm, settings)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close), tint = Color.White) }
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.4f)).padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(statusColor))
            Text(statusText, color = Color.White, fontSize = 12.sp)
        }
        Spacer(Modifier.weight(1f))
        RoundButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings), tint = Color.White) }
    }
}

@Composable
private fun status(vm: PrompterViewModel, settings: AppSettings): Pair<Color, String> {
    if (!settings.voiceMode) {
        return Color(0xFF4DA3FF) to stringResource(if (vm.isPaused) R.string.paused else R.string.auto_scroll)
    }
    if (vm.capture.isInterrupted) return Color.Red to stringResource(R.string.camera_interrupted)
    if (vm.lost) return Color(0xFFFFA726) to stringResource(R.string.finding_position)
    return when (val s = vm.speechStatus) {
        SpeechEngine.Status.Listening -> Color(0xFF34C759) to stringResource(R.string.listening)
        SpeechEngine.Status.Idle -> Color.Gray to stringResource(R.string.idle)
        SpeechEngine.Status.Loading -> Color(0xFFFFD60A) to stringResource(R.string.speech_loading)
        SpeechEngine.Status.Denied -> Color.Red to stringResource(R.string.speech_denied)
        is SpeechEngine.Status.Error -> Color.Red to s.message
    }
}

@Composable
private fun RoundButton(size: androidx.compose.ui.unit.Dp = 40.dp, enabled: Boolean = true, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun RecordingBadge(seconds: Int) {
    Row(
        Modifier
            .padding(start = 16.dp)
            .clip(CircleShape)
            .background(Color.Red.copy(alpha = 0.28f))
            .border(1.dp, Color.Red.copy(alpha = 0.7f), CircleShape)
            .padding(horizontal = 13.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(Icons.Filled.FiberManualRecord, contentDescription = null, Modifier.size(12.dp), tint = Color.Red)
        Text(
            stringResource(R.string.rec_badge, String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)),
            color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun BottomBar(vm: PrompterViewModel, settings: AppSettings) {
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (!settings.voiceMode) {
            Row(
                Modifier
                    .padding(horizontal = 30.dp)
                    .fillMaxWidth()
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.4f))
                    .padding(horizontal = 14.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("🐢", fontSize = 12.sp)
                Slider(
                    value = settings.autoSpeed.toFloat(), onValueChange = { settings.autoSpeed = it.toDouble() },
                    valueRange = 10f..150f, modifier = Modifier.weight(1f),
                )
                Text("🐇", fontSize = 12.sp)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 34.dp), verticalAlignment = Alignment.CenterVertically) {
            // voice / auto mode toggle
            RoundButton(size = 52.dp, onClick = { settings.voiceMode = !settings.voiceMode }) {
                Icon(
                    if (settings.voiceMode) Icons.Filled.Mic else Icons.Filled.Timer,
                    contentDescription = stringResource(if (settings.voiceMode) R.string.mode_voice else R.string.mode_auto),
                    tint = Color.White,
                )
            }
            Spacer(Modifier.weight(1f))
            RecordButton(vm)
            Spacer(Modifier.weight(1f))
            if (!settings.voiceMode) {
                RoundButton(size = 52.dp, onClick = { vm.togglePause() }) {
                    Icon(
                        if (vm.isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                        contentDescription = stringResource(if (vm.isPaused) R.string.resume else R.string.pause),
                        tint = Color.White,
                    )
                }
            } else {
                Spacer(Modifier.size(52.dp))
            }
        }
    }
}

/** idle → 3·2·1 countdown → recording; tap mid-countdown cancels. */
@Composable
private fun RecordButton(vm: PrompterViewModel) {
    val recording = vm.capture.isRecording
    val enabled = vm.capture.canRecord
    val inner by animateDpAsState(if (recording) 30.dp else 62.dp, label = "rec")
    val corner by animateDpAsState(if (recording) 6.dp else 31.dp, label = "corner")
    Box(
        Modifier
            .size(74.dp)
            .alpha(if (enabled) 1f else 0.35f)
            .border(4.dp, Color.White, CircleShape)
            .clip(CircleShape)
            .clickable(enabled = enabled) { vm.recordTapped() },
        contentAlignment = Alignment.Center,
    ) {
        val c = vm.countdown
        if (c != null) {
            Text("$c", color = Color.Red, fontSize = 34.sp, fontWeight = FontWeight.Black)
        } else {
            Box(Modifier.size(inner).clip(RoundedCornerShape(corner)).background(Color.Red).animateContentSize())
        }
    }
}

@Composable
private fun PermissionOverlay(modifier: Modifier, onOpenSettings: () -> Unit, onAutoScroll: () -> Unit) {
    Column(
        modifier
            .padding(24.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black.copy(alpha = 0.85f))
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(Icons.Filled.MicOff, contentDescription = null, Modifier.size(44.dp), tint = Color.White)
        Text(stringResource(R.string.perm_title), color = Color.White, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(stringResource(R.string.perm_body), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, textAlign = TextAlign.Center)
        Button(onClick = onOpenSettings) { Text(stringResource(R.string.open_settings)) }
        OutlinedButton(onClick = onAutoScroll) { Text(stringResource(R.string.switch_to_auto)) }
    }
}
