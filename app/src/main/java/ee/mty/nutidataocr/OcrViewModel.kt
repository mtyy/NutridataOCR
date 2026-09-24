package ee.mty.nutidataocr

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

internal class OcrViewModel : ViewModel() {
    private val scan = NutritionScan()

    var recognizedText by mutableStateOf("")
        private set
    var nutrients by mutableStateOf<Map<Nutrient, List<NutrientValue>>>(emptyMap())
        private set

    fun onTextRecognized(lines: List<OcrLine>, capturedAtMillis: Long) {
        recognizedText = lines.joinToString("\n") { it.text }
        nutrients = scan.observe(lines, capturedAtMillis)
    }

    fun reset(timestampMillis: Long) {
        scan.reset(timestampMillis)
        recognizedText = ""
        nutrients = emptyMap()
    }
}