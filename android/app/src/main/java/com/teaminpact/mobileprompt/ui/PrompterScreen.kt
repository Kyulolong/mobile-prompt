package com.teaminpact.mobileprompt.ui

import android.Manifest
import android.app.Application
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Rational
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
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Videocam
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
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.teaminpact.mobileprompt.MainActivity
import com.teaminpact.mobileprompt.MobilePromptApp
import com.teaminpact.mobileprompt.R
import com.teaminpact.mobileprompt.capture.CaptureController
import com.teaminpact.mobileprompt.engine.DisplayToken
import com.teaminpact.mobileprompt.model.AppSettings
import com.teaminpact.mobileprompt.speech.SpeechEngine
import kotlinx.coroutines.delay
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

    // Floating (Picture-in-Picture), stage mode only. The activity keeps
    // running in PiP — and with it this ViewModel, the scroll loop and the
    // mic — so nothing here needs a service.
    val activity = context as? MainActivity
    val inPip = rememberIsInPipMode(activity)
    val pipSupported = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) }
    var scriptBounds by remember { mutableStateOf<android.graphics.Rect?>(null) }
    var pipDenied by remember { mutableStateOf(false) }
    fun currentPipParams(): PictureInPictureParams? =
        if (settings.stageMode && pipSupported) pipParams(context, scriptBounds, vm.isPaused) else null

    fun granted(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    // Permissions, then start. Camera is optional (script-only fallback), mic
    // is required for voice mode.
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        vm.start(script, settings, micGranted = result[Manifest.permission.RECORD_AUDIO] == true || granted(Manifest.permission.RECORD_AUDIO))
    }
    LaunchedEffect(Unit) {
        // Stage mode never asks for the camera.
        val needCamera = !settings.stageMode
        if ((!needCamera || granted(Manifest.permission.CAMERA)) && granted(Manifest.permission.RECORD_AUDIO)) {
            vm.start(script, settings, micGranted = true)
        } else {
            permissionLauncher.launch(
                if (needCamera) arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                else arrayOf(Manifest.permission.RECORD_AUDIO),
            )
        }
    }
    var lastStage by remember { mutableStateOf(settings.stageMode) }
    LaunchedEffect(settings.stageMode) {
        if (settings.stageMode != lastStage) {
            lastStage = settings.stageMode
            vm.setStageMode(settings.stageMode)
            if (!settings.stageMode && !granted(Manifest.permission.CAMERA)) {
                permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
            }
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
    LaunchedEffect(settings.videoQuality) { vm.capture.changeQuality(settings.videoQuality) }

    // Hardware shutter: registered only while this screen is up, and only
    // swallowed when there is something to do (camera ready or rehearsing).
    val app = context.applicationContext as MobilePromptApp
    DisposableEffect(settings.hardwareShutter) {
        app.hardwareShutter = if (!settings.hardwareShutter) null else ({
            if (!showSettings && (vm.capture.canRecord || vm.rehearsalActive)) {
                vm.hardwareShutterPressed()
                true
            } else {
                false
            }
        })
        onDispose { app.hardwareShutter = null }
    }
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

    // Keep the system's PiP params current (aspect, fly-out rect, buttons,
    // auto-enter on Home). A camera session must never float: its preview
    // can't continue and a take must not be cut short by a Home swipe.
    LaunchedEffect(settings.stageMode, scriptBounds, vm.isPaused, pipSupported) {
        if (activity == null || !pipSupported) return@LaunchedEffect
        val params = currentPipParams()
        if (params != null) {
            activity.setPictureInPictureParams(params)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            activity.setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(false).build())
        }
    }
    DisposableEffect(activity) {
        activity?.pipParamsProvider = { currentPipParams() }
        activity?.pipActionHandler = { action ->
            when (action) {
                MainActivity.ACTION_PIP_PAUSE -> vm.setPausedFromPip(!vm.isPaused)
                MainActivity.ACTION_PIP_BACK -> vm.jumpTokens(-5)
                MainActivity.ACTION_PIP_FORWARD -> vm.jumpTokens(5)
            }
        }
        onDispose {
            activity?.pipParamsProvider = null
            activity?.pipActionHandler = null
            if (activity != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                activity.setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(false).build())
            }
        }
    }
    // Leaving PiP: "expand" brings the activity back to the front; the X
    // button leaves it stopped — that is the user closing the prompter.
    var wasInPip by remember { mutableStateOf(false) }
    LaunchedEffect(inPip) {
        if (wasInPip && !inPip) {
            delay(400) // lifecycle ordering differs across versions; settle first
            if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) close()
        }
        wasInPip = inPip
    }
    // Closing mid-take (X or back) asks first. "Stop and close" then waits for
    // the save dialog — share / back to Perplz — before the prompter goes away;
    // closing right away would clear the ViewModel and lose that dialog.
    var confirmClose by remember { mutableStateOf(false) }
    var closeAfterSave by remember { mutableStateOf(false) }
    fun requestClose() { if (vm.capture.isRecording) confirmClose = true else close() }
    fun finishSaveFlow() {
        vm.capture.saveMessage = null
        if (closeAfterSave) close()
    }
    BackHandler { requestClose() }
    LaunchedEffect(vm.capture.isRecording) { if (!vm.capture.isRecording) confirmClose = false }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Camera (none at all in stage mode, nor in the floating window)
        if (!settings.stageMode && !inPip) when (vm.capture.cameraStatus) {
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
                            vm.capture.quality = settings.videoQuality
                            vm.capture.bind(lifecycleOwner, this)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                // Stage mode on: the preview leaves composition and the camera
                // (and any take) goes with it; off again: the factory rebinds.
                DisposableEffect(Unit) { onDispose { vm.capture.unbind() } }
                if (vm.capture.cameraStatus == CaptureController.CameraStatus.STARTING) {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.White.copy(alpha = 0.35f))
                        Spacer(Modifier.height(10.dp))
                        Text(stringResource(R.string.camera_starting), color = Color.White.copy(alpha = 0.35f), fontSize = 12.sp)
                    }
                }
            }
        }
        if (!settings.stageMode && !inPip) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = settings.scrimOpacity.toFloat())))
        }

        // Always the same slot, so the text view (and its scroll position)
        // survives the PiP transition; only its parameters change.
        ScriptBox(vm, settings, inPip = inPip, onBounds = { scriptBounds = it })

        if (inPip) {
            val (statusColor, _) = status(vm, settings)
            Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(8.dp).clip(CircleShape).background(statusColor))
        } else {
        Column(Modifier.fillMaxSize().safeDrawingPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TopBar(vm, settings, onClose = { requestClose() }, onSettings = { showSettings = true })
            AnimatedVisibility(visible = vm.capture.isRecording) { RecordingBadge(vm.capture.recordingSeconds) }
            AnimatedVisibility(visible = vm.rehearsalActive) { RehearsalBadge(vm.rehearsalSeconds) }
            Spacer(Modifier.weight(1f))
            BottomBar(
                vm, settings,
                floatingEnabled = pipSupported,
                onFloat = {
                    val params = currentPipParams()
                    if (activity == null || params == null || !activity.enterPictureInPictureMode(params)) pipDenied = true
                },
            )
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
        vm.toast?.let { msg ->
            Text(
                msg,
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 130.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.75f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                color = Color.White, fontWeight = FontWeight.Medium,
            )
        }
        vm.errorMessage?.let { msg ->
            Text(
                msg, Modifier.align(Alignment.Center).padding(24.dp),
                color = Color.White, textAlign = TextAlign.Center,
            )
        }
        } // !inPip
    }

    if (pipDenied) {
        AlertDialog(
            onDismissRequest = { pipDenied = false },
            title = { Text(stringResource(R.string.floating)) },
            text = { Text(stringResource(if (pipSupported) R.string.pip_disabled_in_settings else R.string.pip_unsupported)) },
            confirmButton = {
                if (pipSupported) {
                    TextButton(onClick = {
                        pipDenied = false
                        context.startActivity(
                            Intent("android.settings.PICTURE_IN_PICTURE_SETTINGS", Uri.fromParts("package", context.packageName, null)) // Settings.ACTION_PICTURE_IN_PICTURE_SETTINGS is hidden
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }) { Text(stringResource(R.string.open_settings)) }
                } else {
                    TextButton(onClick = { pipDenied = false }) { Text(stringResource(R.string.ok)) }
                }
            },
            dismissButton = if (pipSupported) ({ TextButton(onClick = { pipDenied = false }) { Text(stringResource(R.string.ok)) } }) else null,
        )
    }

    if (showSettings) {
        SettingsSheet(settings = settings, compact = true, onDismiss = { showSettings = false })
    }

    vm.report?.let { report ->
        RehearsalResultSheet(
            report = report,
            onAgain = { vm.startRehearsal() },
            onRecord = { vm.recordTapped() },
            onDismiss = {
                vm.report = null
                if (closeAfterSave) close()
            },
        )
    }

    if (confirmClose) {
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text(stringResource(R.string.close_while_recording_title)) },
            text = { Text(stringResource(R.string.close_while_recording_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClose = false
                    if (vm.capture.isRecording) {
                        closeAfterSave = true
                        vm.capture.stopRecording() // the save dialog follows, then close()
                    } else {
                        close()
                    }
                }) { Text(stringResource(R.string.stop_and_close)) }
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
            onDismissRequest = { finishSaveFlow() },
            title = { Text(stringResource(R.string.notice)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(msg)
                    Spacer(Modifier.height(6.dp))
                    if (vm.takeReportAvailable) {
                        TextButton(onClick = {
                            vm.capture.saveMessage = null
                            vm.showTakeReport() // closes after the sheet, if asked to
                        }) { Text(stringResource(R.string.take_report)) }
                    }
                    if (shareIntent != null) {
                        TextButton(onClick = {
                            context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.share_video_srt)))
                            finishSaveFlow()
                        }) { Text(stringResource(R.string.share_video_srt)) }
                    }
                    if (returnUrl != null) {
                        TextButton(onClick = {
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(returnUrl)))
                            } catch (_: android.content.ActivityNotFoundException) {
                                // Perplz not installed any more: nothing to go back to.
                            }
                            finishSaveFlow()
                        }) { Text(stringResource(R.string.back_to_perplz)) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { finishSaveFlow() }) { Text(stringResource(R.string.ok)) }
            },
        )
    }
}

/** Whether the activity is currently shown as a Picture-in-Picture window. */
@Composable
private fun rememberIsInPipMode(activity: MainActivity?): Boolean {
    var inPip by remember { mutableStateOf(activity?.isInPictureInPictureMode == true) }
    DisposableEffect(activity) {
        val observer = Consumer<PictureInPictureModeChangedInfo> { inPip = it.isInPictureInPictureMode }
        activity?.addOnPictureInPictureModeChangedListener(observer)
        onDispose { activity?.removeOnPictureInPictureModeChangedListener(observer) }
    }
    return inPip
}

private fun pipParams(context: Context, bounds: android.graphics.Rect?, paused: Boolean): PictureInPictureParams {
    fun action(code: Int, broadcast: String, icon: Int, title: Int): RemoteAction {
        val intent = PendingIntent.getBroadcast(
            context, code, Intent(broadcast).setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val label = context.getString(title)
        return RemoteAction(Icon.createWithResource(context, icon), label, label, intent)
    }
    val b = PictureInPictureParams.Builder()
        .setAspectRatio(Rational(16, 9))
        .setActions(
            listOf(
                action(1, MainActivity.ACTION_PIP_BACK, R.drawable.ic_pip_back, R.string.pip_back),
                if (paused) action(2, MainActivity.ACTION_PIP_PAUSE, R.drawable.ic_pip_play, R.string.resume)
                else action(2, MainActivity.ACTION_PIP_PAUSE, R.drawable.ic_pip_pause, R.string.pause),
                action(3, MainActivity.ACTION_PIP_FORWARD, R.drawable.ic_pip_forward, R.string.pip_forward),
            ),
        )
    bounds?.let { b.setSourceRectHint(it) }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        b.setAutoEnterEnabled(true)
        b.setSeamlessResizeEnabled(false)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) b.setTitle(context.getString(R.string.app_name))
    return b.build()
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
private fun ScriptBox(vm: PrompterViewModel, settings: AppSettings, inPip: Boolean, onBounds: (android.graphics.Rect) -> Unit) {
    val density = LocalDensity.current.density
    val configuration = LocalConfiguration.current
    val rotation = LocalView.current.display?.rotation ?: Surface.ROTATION_0
    val built = remember { BuiltState() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        // Stage mode has no lens to hug: the box settings apply in every
        // orientation. The PiP window is all script: full width, centred line.
        val hugCamera = landscape && !settings.stageMode && !inPip
        val boxWidth = if (inPip) maxWidth else if (hugCamera) maxWidth * 0.55f else maxWidth * settings.portraitWidthFrac.toFloat()
        val topPad = if (hugCamera || inPip) 0.dp else maxHeight * settings.portraitTopFrac.toFloat()
        val bottomPad = if (hugCamera || inPip) 0.dp else maxHeight * settings.portraitBottomFrac.toFloat()
        val bandHeight = maxHeight - topPad - bottomPad
        val readingLineFrac = if (inPip) 0.5f else settings.readingLineFrac.toFloat()
        // ROTATION_90: the phone's top edge (camera) is on the left.
        val cameraOnLeft = rotation == Surface.ROTATION_90
        @Suppress("UNUSED_VARIABLE") val relayoutKey = configuration.orientation

        Row(Modifier.fillMaxSize()) {
            if (!hugCamera || !cameraOnLeft) Spacer(Modifier.weight(1f, fill = true))
            AndroidView(
                factory = { ctx ->
                    PromptTextView(ctx).apply {
                        onLayoutChange = { vm.attach(this, tokenOffsets(), density) }
                        onTap = { vm.tapped(it) }
                    }
                },
                update = { tv ->
                    val size = if (inPip) settings.pipFontSize else if (settings.stageMode) settings.stageFontSize else settings.fontSize
                    val fontPx = (size * density).toFloat()
                    if (built.tokens !== vm.tokens || built.fontPx != fontPx) {
                        built.tokens = vm.tokens
                        built.fontPx = fontPx
                        tv.setContent(vm.tokens, fontPx, if (vm.scriptLang == com.teaminpact.mobileprompt.engine.EngineLang.KO) Locale.KOREAN else Locale.ENGLISH)
                    }
                    tv.setReadingLineFrac(readingLineFrac)
                },
                modifier = Modifier
                    .width(boxWidth)
                    .fillMaxSize()
                    .padding(top = topPad, bottom = bottomPad)
                    .graphicsLayer(scaleX = if (settings.mirrored && !inPip) -1f else 1f)
                    .onGloballyPositioned { coords ->
                        val r = coords.boundsInWindow()
                        onBounds(android.graphics.Rect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt()))
                    },
            )
            if (!hugCamera || cameraOnLeft) Spacer(Modifier.weight(1f, fill = true))
        }

        // Reading-line marker, aligned with the (possibly inset) box.
        if (!inPip) {
            Box(
                Modifier
                    .offset(y = topPad + bandHeight * readingLineFrac)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.18f)),
            )
        }
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
        // Stage mode: camera off, script only.
        Box(Modifier.alpha(if (vm.capture.isRecording) 0.35f else 1f)) {
            RoundButton(enabled = !vm.capture.isRecording, onClick = { settings.stageMode = !settings.stageMode }) {
                Icon(
                    if (settings.stageMode) Icons.Filled.VideocamOff else Icons.Filled.Videocam,
                    contentDescription = stringResource(if (settings.stageMode) R.string.camera_on else R.string.camera_off),
                    tint = Color.White,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        RoundButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings), tint = Color.White) }
    }
}

@Composable
private fun status(vm: PrompterViewModel, settings: AppSettings): Pair<Color, String> {
    if (!settings.voiceMode) {
        return Color(0xFF4DA3FF) to stringResource(if (vm.isPaused) R.string.paused else R.string.auto_scroll)
    }
    if (!settings.stageMode && vm.capture.isInterrupted) return Color.Red to stringResource(R.string.camera_interrupted)
    if (vm.speechStatus == SpeechEngine.Status.Silenced) return Color(0xFFFFA726) to stringResource(R.string.mic_taken_auto)
    if (vm.lost) return Color(0xFFFFA726) to stringResource(R.string.finding_position)
    return when (val s = vm.speechStatus) {
        SpeechEngine.Status.Silenced -> Color(0xFFFFA726) to stringResource(R.string.mic_taken_auto)
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
private fun RehearsalBadge(seconds: Int) {
    val cyan = Color(0xFF32ADE6)
    Row(
        Modifier
            .padding(start = 16.dp)
            .clip(CircleShape)
            .background(cyan.copy(alpha = 0.22f))
            .border(1.dp, cyan.copy(alpha = 0.6f), CircleShape)
            .padding(horizontal = 13.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(Icons.Filled.GraphicEq, contentDescription = null, Modifier.size(12.dp), tint = cyan)
        Text(
            stringResource(R.string.rehearsing, String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)),
            color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun BottomBar(vm: PrompterViewModel, settings: AppSettings, floatingEnabled: Boolean, onFloat: () -> Unit) {
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
            if (settings.stageMode) FloatButton(enabled = floatingEnabled, onClick = onFloat) else RecordButton(vm)
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
                val busy = vm.capture.isRecording || vm.countdown != null
                Box(Modifier.alpha(if (busy) 0.35f else 1f)) {
                    RoundButton(
                        size = 52.dp, enabled = !busy,
                        onClick = { if (vm.rehearsalActive) vm.finishRehearsal() else vm.startRehearsal() },
                    ) {
                        Icon(
                            if (vm.rehearsalActive) Icons.Filled.Stop else Icons.Filled.School,
                            contentDescription = stringResource(if (vm.rehearsalActive) R.string.finish else R.string.rehearse),
                            tint = Color.White,
                        )
                    }
                }
            }
        }
    }
}

/** Stage mode's big button: pop the script out into a floating (PiP) window. */
@Composable
private fun FloatButton(enabled: Boolean, onClick: () -> Unit) {
    val label = stringResource(R.string.start_floating)
    Box(
        Modifier
            .size(74.dp)
            .alpha(if (enabled) 1f else 0.35f)
            .border(4.dp, Color.White, CircleShape)
            .clip(CircleShape)
            .semantics { contentDescription = label }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.PictureInPictureAlt, contentDescription = null, Modifier.size(30.dp), tint = Color.White)
    }
}

/** idle → 3·2·1 countdown → recording; tap mid-countdown cancels. */
@Composable
private fun RecordButton(vm: PrompterViewModel) {
    val recording = vm.capture.isRecording
    val enabled = vm.capture.canRecord && !vm.rehearsalActive
    val inner by animateDpAsState(if (recording) 30.dp else 62.dp, label = "rec")
    val corner by animateDpAsState(if (recording) 6.dp else 31.dp, label = "corner")
    val label = stringResource(if (recording) R.string.stop_recording else R.string.record)
    Box(
        Modifier
            .size(74.dp)
            .alpha(if (enabled) 1f else 0.35f)
            .border(4.dp, Color.White, CircleShape)
            .clip(CircleShape)
            .semantics { contentDescription = label }
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
