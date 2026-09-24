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
    var stableNutrients by mutableStateOf<Set<Nutrient>>(emptySet())
        private set
    var manualNutrients by mutableStateOf<Map<Nutrient, NutrientValue>>(emptyMap())
        private set
    var numericFallback by mutableStateOf(NumericFallbackResult())
        private set

    val effectiveNutrients: Map<Nutrient, List<NutrientValue>>
        get() = nutrients + manualNutrients.mapValues { listOf(it.value) }

    val readyNutrients: Set<Nutrient>
        get() = stableNutrients + manualNutrients.keys

    fun setManualNutrient(nutrient: Nutrient, input: String): Boolean {
        if (nutrient !in REQUIRED_SCAN_NUTRIENTS || input.length > 64) return false
        val text = input.trim()
        if (!Regex("[<>]?\\s*(?:\\d+(?:[.,]\\d+)?|[.,]\\d+)").matches(text)) return false
        val comparison = text.takeWhile { it == '<' || it == '>' }
        val amount = text.removePrefix(comparison).trim().replace(',', '.').toBigDecimalOrNull() ?: return false
        val value = NutrientValue(comparison + amount.stripTrailingZeros().toPlainString(), "g")
        manualNutrients = manualNutrients + (nutrient to value)
        return true
    }

    fun useOcr(nutrient: Nutrient) {
        manualNutrients = manualNutrients - nutrient
    }

    fun manualEntryAmount(nutrient: Nutrient): String {
        val value = effectiveNutrients[nutrient]?.singleOrNull() ?: return ""
        val comparison = value.amount.takeWhile { it == '<' || it == '>' }
        val number = value.amount.removePrefix(comparison).toBigDecimalOrNull() ?: return ""
        val grams = when (value.unit) {
            "g" -> number
            "mg" -> number.movePointLeft(3)
            else -> return ""
        }
        return comparison + grams.stripTrailingZeros().toPlainString()
    }

    fun onTextRecognized(lines: List<OcrLine>, capturedAtMillis: Long, isPhoto: Boolean = false) {
        if (capturedAtMillis <= resetAtMillis) return
        recognizedText = lines.joinToString("\n") { it.text }
        nutrients = scan.observe(lines, capturedAtMillis, isPhoto)
        stableNutrients = scan.stableNutrients
        numericFallback = fallback.observe(lines, capturedAtMillis, nutrients, isPhoto)
    }

    fun reset(timestampMillis: Long) {
        resetAtMillis = timestampMillis
        scan.reset(timestampMillis)
        fallback.reset(timestampMillis)
        recognizedText = ""
        nutrients = emptyMap()
        stableNutrients = emptySet()
        manualNutrients = emptyMap()
        numericFallback = NumericFallbackResult()
    }
}