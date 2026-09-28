package ee.mty.nutidataocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Records ML Kit output for label photos pushed by `tools/capture_label_ocr.sh`; skipped when none are present. */
@RunWith(AndroidJUnit4::class)
class LabelOcrCapture {
    @Test
    fun captureSampleLabels() {
        val files = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        val photos = files.resolve("labels").listFiles { file -> file.extension.lowercase() in PHOTO_TYPES }.orEmpty().sorted()
        assumeTrue(photos.isNotEmpty())
        val output = files.resolve("ocr")
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        for (photo in photos) {
            val original = BitmapFactory.decodeFile(photo.path)
            for ((variant, longSide) in VARIANTS) {
                val bitmap = if (longSide == null) original else original.scaledTo(longSide)
                val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
                val json = JSONObject()
                    .put("width", bitmap.width).put("height", bitmap.height)
                    .put("lines", JSONArray(text.toOcrLines().map { it.toJson() }))
                output.resolve(variant).apply { mkdirs() }.resolve("${photo.nameWithoutExtension}.json").writeText(json.toString())
                if (bitmap !== original) bitmap.recycle()
            }
            original.recycle()
        }
        recognizer.close()
    }

    private fun Bitmap.scaledTo(longSide: Int): Bitmap {
        val scale = longSide.toFloat() / maxOf(width, height)
        return Bitmap.createScaledBitmap(this, (width * scale).toInt(), (height * scale).toInt(), true)
    }

    private fun OcrLine.toJson() = JSONObject()
        .put("text", text).put("confidence", confidence.toDouble()).put("textHeightPx", textHeightPx.toDouble())
        .put("tokens", JSONArray(tokens.map { token ->
            JSONObject().put("text", token.text).put("confidence", token.confidence.toDouble()).apply {
                token.box?.let { put("box", JSONArray(listOf(it.centerX, it.centerY, it.width, it.height, it.angle))) }
            }
        }))

    private companion object {
        val PHOTO_TYPES = setOf("jpg", "jpeg", "png")
        // photo = ImageCapture at full resolution; frame = the old ImageAnalysis default of 640x480.
        val VARIANTS = listOf("photo" to null, "frame1280" to 1280, "frame" to 640)
    }
}
