package ee.mty.nutidataocr

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.widget.Toast
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text as ComposeText
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import kotlin.math.hypot

@androidx.annotation.OptIn(ExperimentalGetImage::class, ExperimentalCamera2Interop::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CameraPreview(
    onTextRecognized: (List<OcrLine>, Long, Boolean) -> Unit,
    onGeminiPhoto: (File, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val latestOnTextRecognized by rememberUpdatedState(onTextRecognized)
    val latestOnGeminiPhoto by rememberUpdatedState(onGeminiPhoto)
    var rearCameras by remember { mutableStateOf(emptyList<RearCamera>()) }
    var selectedCameraId by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraMenuExpanded by remember { mutableStateOf(false) }
    var takePhoto by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    var capturing by remember { mutableStateOf(false) }
    val physicalCameraId = rearCameras.firstOrNull { it.id == selectedCameraId }?.selector?.physicalCameraId

    DisposableEffect(previewView, lifecycleOwner, selectedCameraId, physicalCameraId) {
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
        val requestedCameraId = selectedCameraId
        var lastCaptureInfo: String? = null
        val previewBuilder = Preview.Builder().setTargetRotation(rotation)
        physicalCameraId?.let { Camera2Interop.Extender(previewBuilder).setPhysicalCameraId(it) }
        Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(
            object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    val activePhysicalId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
                    } else null
                    val physicalFocalLengths = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        result.physicalCameraResults.mapValues { (_, metadata) ->
                            metadata.get(CaptureResult.LENS_FOCAL_LENGTH)
                        }
                    } else emptyMap()
                    val captureInfo = "requested=$requestedCameraId active=$activePhysicalId " +
                        "routed=$physicalCameraId focalLength=${result.get(CaptureResult.LENS_FOCAL_LENGTH)} " +
                        "physicalFocalLengths=$physicalFocalLengths"
                    if (captureInfo != lastCaptureInfo) {
                        Log.i("CameraOcr", "Rear camera frame: $captureInfo")
                        lastCaptureInfo = captureInfo
                    }
                }
            }
        )
        val preview = previewBuilder.build().apply {
            surfaceProvider = previewView.surfaceProvider
        }
        val analysis = run {
            val builder = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetRotation(rotation)
            physicalCameraId?.let { Camera2Interop.Extender(builder).setPhysicalCameraId(it) }
            builder.build()
        }
        val imageCapture = run {
            val builder = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setFlashMode(ImageCapture.FLASH_MODE_OFF)
                .setTargetRotation(rotation)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
                        .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                        .build()
                )
            physicalCameraId?.let { Camera2Interop.Extender(builder).setPhysicalCameraId(it) }
            builder.build()
        }
        val useCases = arrayOf(preview, analysis, imageCapture)
        var provider: ProcessCameraProvider? = null
        var boundCameraInfo: CameraInfo? = null
        val cameraStateObserver = Observer<CameraState> { state ->
            state.error?.let { error ->
                Log.e("CameraOcr", "Rear camera $requestedCameraId error ${error.code}", error.cause)
            }
        }
        var disposed = false
        var processing = false
        var capturePending = false
        var pendingPhotoCapture: (() -> Unit)? = null

        fun finishProcessing(isPhoto: Boolean) {
            processing = false
            if (isPhoto) capturePending = false
            if (disposed) {
                recognizer.close()
            } else {
                if (isPhoto) capturing = false
                val capture = pendingPhotoCapture
                pendingPhotoCapture = null
                capture?.invoke()
            }
        }

        fun showPhotoFailure() {
            if (!disposed) {
                Toast.makeText(context, R.string.photo_scan_failed, Toast.LENGTH_SHORT).show()
            }
        }

        fun processFrame(frame: ImageProxy, capturedAtMillis: Long, isPhoto: Boolean = false) {
            val image = frame.image
            if (image == null) {
                frame.close()
                finishProcessing(isPhoto)
                if (isPhoto) showPhotoFailure()
                return
            }
            processing = true
            try {
                recognizer.process(InputImage.fromMediaImage(image, frame.imageInfo.rotationDegrees))
                    .addOnCompleteListener(mainExecutor) { task ->
                        frame.close()
                        finishProcessing(isPhoto)
                        if (disposed) return@addOnCompleteListener
                        if (task.isSuccessful) {
                            val lines = task.result.textBlocks.flatMap { it.lines }.map { line ->
                                val numbers = line.elements.filter { element ->
                                    element.text.any { it.isDigit() }
                                }
                                OcrLine(
                                    text = line.text,
                                    confidence = minOf(
                                        line.confidence,
                                        numbers.minOfOrNull { it.confidence } ?: line.confidence,
                                    ),
                                    textHeightPx = numbers.minOfOrNull { it.textHeightPx() } ?: 24f,
                                    tokens = line.elements.map { OcrToken(it.text, it.confidence) },
                                )
                            }
                            latestOnTextRecognized(lines, capturedAtMillis, isPhoto)
                            if (isPhoto) {
                                Toast.makeText(
                                    context,
                                    if (lines.isEmpty()) R.string.photo_no_text else R.string.photo_scanned,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        } else {
                            Log.e("CameraOcr", "Text recognition failed", task.exception)
                            if (isPhoto) showPhotoFailure()
                        }
                    }
            } catch (exception: Exception) {
                frame.close()
                finishProcessing(isPhoto)
                Log.e("CameraOcr", "Could not process camera frame", exception)
                if (isPhoto) showPhotoFailure()
            }
        }

        analysis.setAnalyzer(mainExecutor) { frame ->
            if (disposed || processing || capturePending) {
                frame.close()
            } else {
                processFrame(frame, SystemClock.elapsedRealtime())
            }
        }

        providerFuture.addListener({
            if (!disposed) {
                try {
                    val cameraProvider = providerFuture.get()
                    provider = cameraProvider
                    val cameras = rearCameras.ifEmpty { availableRearCameras(context, cameraProvider) }
                    rearCameras = cameras
                    val selected = cameras.firstOrNull { it.id == selectedCameraId } ?: cameras.firstOrNull()
                    if (selected?.selector?.physicalCameraId != physicalCameraId) return@addListener
                    Log.i("CameraOcr", "Opening rear camera ${selected?.id}: ${selected?.label}")
                    val camera = cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        selected?.selector ?: CameraSelector.DEFAULT_BACK_CAMERA,
                        *useCases,
                    )
                    boundCameraInfo = camera.cameraInfo
                    camera.cameraInfo.cameraState.observe(lifecycleOwner, cameraStateObserver)
                    takePhoto = { forGemini ->
                        if (!disposed && !capturePending) {
                            capturePending = true
                            capturing = true
                            val capturedAtMillis = SystemClock.elapsedRealtime()
                            val capture = {
                                imageCapture.targetRotation = previewView.display?.rotation ?: rotation
                                var shareFile: File? = null
                                try {
                                    if (forGemini) {
                                        val photo = createGeminiPhotoFile(context)
                                        shareFile = photo
                                        imageCapture.takePicture(
                                            ImageCapture.OutputFileOptions.Builder(photo).build(),
                                            mainExecutor,
                                            object : ImageCapture.OnImageSavedCallback {
                                                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                                    capturePending = false
                                                    if (disposed) {
                                                        photo.delete()
                                                    } else {
                                                        capturing = false
                                                        latestOnGeminiPhoto(photo, capturedAtMillis)
                                                    }
                                                }

                                                override fun onError(exception: ImageCaptureException) {
                                                    photo.delete()
                                                    capturePending = false
                                                    if (!disposed) capturing = false
                                                    Log.e("CameraOcr", "Could not save Gemini photo", exception)
                                                    showPhotoFailure()
                                                }
                                            },
                                        )
                                    } else imageCapture.takePicture(mainExecutor, object : ImageCapture.OnImageCapturedCallback() {
                                        override fun onCaptureSuccess(image: ImageProxy) {
                                            if (disposed) {
                                                image.close()
                                            } else {
                                                Log.i("CameraOcr", "Photo: ${image.width}x${image.height}, rotation=${image.imageInfo.rotationDegrees}")
                                                processFrame(image, capturedAtMillis, isPhoto = true)
                                            }
                                        }

                                        override fun onError(exception: ImageCaptureException) {
                                            capturePending = false
                                            if (!disposed) capturing = false
                                            Log.e("CameraOcr", "Could not capture photo", exception)
                                            showPhotoFailure()
                                        }
                                    })
                                } catch (exception: Exception) {
                                    shareFile?.delete()
                                    capturePending = false
                                    capturing = false
                                    Log.e("CameraOcr", "Could not start photo capture", exception)
                                    showPhotoFailure()
                                }
                            }
                            if (processing) pendingPhotoCapture = capture else capture()
                        }
                    }
                } catch (exception: Exception) {
                    Log.e("CameraOcr", "Could not start rear camera $requestedCameraId", exception)
                    if (selectedCameraId != null) {
                        Toast.makeText(context, R.string.camera_switch_failed, Toast.LENGTH_SHORT).show()
                        selectedCameraId = null
                    }
                }
            }
        }, mainExecutor)

        onDispose {
            disposed = true
            takePhoto = null
            capturing = false
            pendingPhotoCapture = null
            analysis.clearAnalyzer()
            boundCameraInfo?.cameraState?.removeObserver(cameraStateObserver)
            provider?.unbind(*useCases)
            if (!processing) recognizer.close()
        }
    }

    Column(modifier) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxWidth().weight(1f))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (rearCameras.size > 1) {
                Box(Modifier.weight(1f)) {
                    TextButton(onClick = { cameraMenuExpanded = true }) {
                        ComposeText(
                            rearCameras.firstOrNull { it.id == selectedCameraId }?.label
                                ?: rearCameras.first().label,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = cameraMenuExpanded)
                    }
                    DropdownMenu(
                        expanded = cameraMenuExpanded,
                        onDismissRequest = { cameraMenuExpanded = false },
                    ) {
                        rearCameras.forEach { camera ->
                            DropdownMenuItem(
                                text = { ComposeText(camera.label) },
                                onClick = {
                                    cameraMenuExpanded = false
                                    selectedCameraId = camera.id
                                },
                            )
                        }
                    }
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
            FilledIconButton(
                onClick = { takePhoto?.invoke(false) },
                enabled = takePhoto != null && !capturing,
                modifier = Modifier.size(56.dp),
            ) {
                if (capturing) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        painter = painterResource(android.R.drawable.ic_menu_camera),
                        contentDescription = stringResource(R.string.take_photo),
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            FilledTonalButton(
                onClick = {
                    copyGeminiPrompt(context)
                    takePhoto?.invoke(true)
                },
                enabled = takePhoto != null && !capturing,
            ) {
                Icon(
                    painter = painterResource(android.R.drawable.ic_menu_share),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                ComposeText(stringResource(R.string.gemini), modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}

private data class RearCamera(val id: String, val label: String, val selector: CameraSelector)

@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
private fun availableRearCameras(context: Context, provider: ProcessCameraProvider): List<RearCamera> {
    val manager = context.getSystemService(CameraManager::class.java)
    return CameraSelector.DEFAULT_BACK_CAMERA.filter(provider.availableCameraInfos).flatMap { info ->
        val logicalId = Camera2CameraInfo.from(info).cameraId
        val characteristics = manager.getCameraCharacteristics(logicalId)
        val physicalIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            characteristics.physicalCameraIds.sorted()
        } else {
            emptyList()
        }
        val logicalCamera = RearCamera(
            id = logicalId,
            label = if (physicalIds.isNotEmpty()) {
                context.getString(R.string.rear_camera_auto, logicalId)
            } else {
                rearCameraLabel(context, logicalId, characteristics)
            },
            selector = info.cameraSelector,
        )
        listOf(logicalCamera) + physicalIds.mapNotNull { physicalId ->
            try {
                RearCamera(
                    id = "$logicalId/$physicalId",
                    label = rearCameraLabel(context, physicalId, manager.getCameraCharacteristics(physicalId)),
                    selector = CameraSelector.Builder()
                        .addCameraFilter { cameras -> info.cameraSelector.filter(cameras) }
                        .setPhysicalCameraId(physicalId)
                        .build(),
                )
            } catch (exception: Exception) {
                Log.w("CameraOcr", "Could not inspect rear lens $physicalId", exception)
                null
            }
        }
    }
}

private fun rearCameraLabel(context: Context, id: String, characteristics: CameraCharacteristics): String {
    val focalLength = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
    return if (focalLength != null) {
        context.getString(R.string.rear_camera_focal_length, id, focalLength)
    } else {
        context.getString(R.string.rear_camera, id)
    }
}

private fun Text.Element.textHeightPx(): Float {
    val corners = cornerPoints
    if (corners == null || corners.size != 4) return boundingBox?.height()?.toFloat() ?: 24f
    return minOf(
        hypot((corners[3].x - corners[0].x).toFloat(), (corners[3].y - corners[0].y).toFloat()),
        hypot((corners[2].x - corners[1].x).toFloat(), (corners[2].y - corners[1].y).toFloat()),
    )
}