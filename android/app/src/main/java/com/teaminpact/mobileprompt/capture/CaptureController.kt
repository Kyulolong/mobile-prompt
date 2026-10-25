package com.teaminpact.mobileprompt.capture

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.teaminpact.mobileprompt.R
import com.teaminpact.mobileprompt.engine.Clock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Front-camera preview and movie recording through CameraX.
 *
 * Unlike the iOS build, recording does not have to share its microphone with
 * speech recognition: SpeechEngine opens its own AudioRecord, and Android lets
 * one app run several capture clients at once. So this class only owns the
 * camera. Recordings go straight to the gallery via MediaStore.
 */
class CaptureController(private val context: Context) {
    enum class CameraStatus { STARTING, READY, UNAVAILABLE }

    var cameraStatus by mutableStateOf(CameraStatus.STARTING)
        private set
    var isRecording by mutableStateOf(false)
        private set
    var recordingSeconds by mutableIntStateOf(0)
        private set
    /** Another app holds the camera, or it failed to open; preview is dark. */
    var isInterrupted by mutableStateOf(false)
        private set
    var saveMessage by mutableStateOf<String?>(null)
    /** Host-clock seconds when the recording started (same clock as word times). */
    var lastRecordingStart: Double? = null
        private set
    /** MediaStore URI of the last saved recording, for sharing with its subtitles. */
    var lastRecordingUri by mutableStateOf<Uri?>(null)
        private set

    var onRecordingStarted: (() -> Unit)? = null
    /** Called when a recording ends, before [saveMessage] is set. */
    var onRecordingFinished: (() -> Unit)? = null

    val cameraAvailable: Boolean get() = cameraStatus == CameraStatus.READY
    /** Preview is up AND the camera offers a recordable quality. */
    val canRecord: Boolean get() = cameraStatus == CameraStatus.READY && videoCapture != null

    private var provider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private val mainExecutor = ContextCompat.getMainExecutor(context)

    /** Bind preview + recorder to the owner's lifecycle; CameraX handles pause/resume. */
    fun bind(owner: LifecycleOwner, previewView: PreviewView) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val p = try { future.get() } catch (e: Exception) {
                Log.e(TAG, "camera provider unavailable", e)
                cameraStatus = CameraStatus.UNAVAILABLE
                return@addListener
            }
            provider = p
            val selector = when {
                p.safeHasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                p.safeHasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                else -> {
                    cameraStatus = CameraStatus.UNAVAILABLE
                    return@addListener
                }
            }
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.fromOrderedList(
                        listOf(Quality.FHD, Quality.HD, Quality.SD, Quality.LOWEST),
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
                    ),
                )
                .build()
            val vc = VideoCapture.withOutput(recorder)
            try {
                p.unbindAll()
                val camera = try {
                    p.bindToLifecycle(owner, selector, preview, vc).also { videoCapture = vc }
                } catch (e: IllegalArgumentException) {
                    // No recordable quality on this camera (seen on emulators):
                    // keep the live preview, just without the record button.
                    Log.w(TAG, "video capture unsupported; preview only", e)
                    p.unbindAll()
                    videoCapture = null
                    p.bindToLifecycle(owner, selector, preview)
                }
                cameraStatus = CameraStatus.READY
                camera.cameraInfo.cameraState.observe(owner) { st ->
                    isInterrupted = st.type == CameraState.Type.PENDING_OPEN ||
                        (st.error != null && st.type != CameraState.Type.OPEN)
                }
            } catch (e: Exception) {
                Log.e(TAG, "bind failed", e)
                cameraStatus = CameraStatus.UNAVAILABLE
            }
        }, mainExecutor)
    }

    private fun ProcessCameraProvider.safeHasCamera(selector: CameraSelector): Boolean =
        try { hasCamera(selector) } catch (_: Exception) { false }

    fun unbind() {
        recording?.stop()
        recording = null
        provider?.unbindAll()
        videoCapture = null
    }

    // MARK: recording

    @SuppressLint("MissingPermission") // checked right below
    fun startRecording() {
        val vc = videoCapture ?: return
        if (recording != null) return
        val name = "VoicePrompter-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$name.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Voice Prompter")
        }
        val options = MediaStoreOutputOptions.Builder(context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values)
            .build()
        var pending = vc.output.prepareRecording(context, options)
        val micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (micGranted) pending = pending.withAudioEnabled()
        saveMessage = null
        lastRecordingUri = null
        recording = pending.start(mainExecutor) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    lastRecordingStart = Clock.now()
                    recordingSeconds = 0
                    isRecording = true
                    onRecordingStarted?.invoke()
                }
                is VideoRecordEvent.Status -> {
                    recordingSeconds = (event.recordingStats.recordedDurationNanos / 1_000_000_000L).toInt()
                }
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    isRecording = false
                    onRecordingFinished?.invoke()
                    val saved = !event.hasError() || event.outputResults.outputUri != Uri.EMPTY
                    if (event.hasError()) Log.w(TAG, "recording finalized with error ${event.error}", event.cause)
                    lastRecordingUri = event.outputResults.outputUri.takeIf { saved && it != Uri.EMPTY }
                    saveMessage = context.getString(if (saved) R.string.saved_gallery else R.string.save_failed)
                }
            }
        }
    }

    fun stopRecording() {
        recording?.stop()
    }

    companion object {
        private const val TAG = "CaptureController"
    }
}
