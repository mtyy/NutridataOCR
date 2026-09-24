package ee.mty.nutidataocr

import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.hypot

@androidx.annotation.OptIn(ExperimentalGetImage::class)
@Composable
internal fun CameraPreview(
    onTextRecognized: (List<OcrLine>, Long) -> Unit,
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

    DisposableEffect(previewView, lifecycleOwner) {
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
        val preview = Preview.Builder().setTargetRotation(rotation).build().apply {
            surfaceProvider = previewView.surfaceProvider
        }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(rotation)
            .build()
        var provider: ProcessCameraProvider? = null
        var disposed = false
        var processing = false

        analysis.setAnalyzer(mainExecutor) { frame ->
            if (disposed || processing) {
                frame.close()
                return@setAnalyzer
            }
            val image = frame.image
            if (image == null) {
                frame.close()
                return@setAnalyzer
            }
            processing = true
            val capturedAtMillis = SystemClock.elapsedRealtime()
            try {
                recognizer.process(InputImage.fromMediaImage(image, frame.imageInfo.rotationDegrees))
                    .addOnCompleteListener(mainExecutor) { task ->
                        frame.close()
                        processing = false
                        if (disposed) {
                            recognizer.close()
                        } else if (task.isSuccessful) {
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
                                )
                            }
                            latestOnTextRecognized(lines, capturedAtMillis)
                        } else {
                            Log.e("CameraOcr", "Text recognition failed", task.exception)
                        }
                    }
            } catch (exception: Exception) {
                frame.close()
                processing = false
                Log.e("CameraOcr", "Could not process camera frame", exception)
            }
        }

        providerFuture.addListener({
            if (!disposed) {
                try {
                    val cameraProvider = providerFuture.get()
                    provider = cameraProvider
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                    )
                } catch (exception: Exception) {
                    Log.e("CameraOcr", "Could not start camera", exception)
                }
            }
        }, mainExecutor)

        onDispose {
            disposed = true
            analysis.clearAnalyzer()
            provider?.unbind(preview, analysis)
            if (!processing) recognizer.close()
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

private fun Text.Element.textHeightPx(): Float {
    val corners = cornerPoints
    if (corners == null || corners.size != 4) return boundingBox?.height()?.toFloat() ?: 24f
    return minOf(
        hypot((corners[3].x - corners[0].x).toFloat(), (corners[3].y - corners[0].y).toFloat()),
        hypot((corners[2].x - corners[1].x).toFloat(), (corners[2].y - corners[1].y).toFloat()),
    )
}