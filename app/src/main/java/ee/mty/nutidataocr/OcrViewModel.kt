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
    var geminiNutrients by mutableStateOf<Map<Nutrient, List<NutrientValue>>>(emptyMap())
        private set
    var numericFallback by mutableStateOf(NumericFallbackResult())
        private set

    val effectiveNutrients: Map<Nutrient, List<NutrientValue>>
        get() = nutrients + geminiNutrients + manualNutrients.mapValues { listOf(it.value) }

    val readyNutrients: Set<Nutrient>
        get() = (stableNutrients - geminiNutrients.keys) +
            geminiNutrients.filterValues { it.size == 1 }.keys + manualNutrients.keys

    fun importGemini(response: GeminiNutrition): Boolean {
        val column = response.columns.filter {
            Regex("(?i)(?<![\\d.,])100\\s*g\\b").containsMatchIn(it.basis.orEmpty())
        }.singleOrNull() ?: response.columns.singleOrNull() ?: return false
        importGeminiColumn(column)
        return true
    }

    fun importGeminiColumn(column: GeminiColumn) {
        val byName = REQUIRED_SCAN_NUTRIENTS.associateBy { it.name.lowercase() }
        geminiNutrients = column.nutrients.mapNotNull { entry ->
            val nutrient = byName[entry.name] ?: return@mapNotNull null
            val text = entry.amount.trim()
            val comparison = text.takeWhile { it == '<' || it == '>' }
            val number = text.removePrefix(comparison).trim().replace(',', '.').toBigDecimal()
            val grams = if (entry.unit == "mg") number.movePointLeft(3) else number
            nutrient to NutrientValue(comparison + grams.stripTrailingZeros().toPlainString(), "g")
        }.groupBy({ it.first }, { it.second }).mapValues { it.value.distinct() }
    }

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
        geminiNutrients = geminiNutrients - nutrient
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
        geminiNutrients = emptyMap()
        numericFallback = NumericFallbackResult()
    }
}