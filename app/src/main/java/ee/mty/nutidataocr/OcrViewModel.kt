package ee.mty.nutidataocr

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

internal class OcrViewModel : ViewModel() {
    private val scan = NutritionScan()
    private val fallback = NutritionFallback()
    private var resetAtMillis = Long.MIN_VALUE

    var recognizedText by mutableStateOf("")
        private set
    var nutrients by mutableStateOf<Map<Nutrient, List<NutrientValue>>>(emptyMap())
        private set
    var numericFallback by mutableStateOf(NumericFallbackResult())
        private set

    fun onTextRecognized(lines: List<OcrLine>, capturedAtMillis: Long) {
        if (capturedAtMillis <= resetAtMillis) return
        recognizedText = lines.joinToString("\n") { it.text }
        nutrients = scan.observe(lines, capturedAtMillis)
        numericFallback = fallback.observe(lines, capturedAtMillis, nutrients)
    }

    fun reset(timestampMillis: Long) {
        resetAtMillis = timestampMillis
        scan.reset(timestampMillis)
        fallback.reset(timestampMillis)
        recognizedText = ""
        nutrients = emptyMap()
        numericFallback = NumericFallbackResult()
    }
}