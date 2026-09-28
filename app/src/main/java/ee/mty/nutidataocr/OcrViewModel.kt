package ee.mty.nutidataocr

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import java.math.BigDecimal

internal enum class ScanMode { AUTO, LINE, SPATIAL }

internal class OcrViewModel : ViewModel() {
    private val scan = NutritionScan()
    private val fallback = NutritionFallback()
    private val layout = LabelLayoutTracker()
    private val auto = AutoNutrition()
    private var resetAtMillis = Long.MIN_VALUE

    var mode by mutableStateOf(ScanMode.AUTO)
        private set
    var layoutWords by mutableStateOf<List<OcrToken>>(emptyList())
        private set
    var layoutFragmentCount by mutableIntStateOf(0)
        private set
    var recognizedText by mutableStateOf("")
        private set
    var nutrients by mutableStateOf<Map<Nutrient, List<NutrientValue>>>(emptyMap())
        private set
    var stableNutrients by mutableStateOf<Set<Nutrient>>(emptySet())
        private set
    var correctedNutrients by mutableStateOf<Set<Nutrient>>(emptySet())
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

    val unidentifiedAmounts: List<String>
        get() {
            val assigned = (nutrients.values.flatten() + effectiveNutrients.values.flatten())
                .mapNotNull(::suggestionAmount).toSet()
            return numericFallback.numbers.mapNotNull { suggestionAmount(it.number) }
                .filterNot { it in assigned }.distinct()
        }

    private fun suggestionAmount(value: NutrientValue): String? {
        val number = value.amount.replace(',', '.').toBigDecimalOrNull() ?: return null
        val grams = when (value.unit) {
            "", "g" -> number
            "mg" -> number.movePointLeft(3)
            else -> return null
        }
        return grams.takeIf { it >= BigDecimal.ZERO && it <= BigDecimal(100) }
            ?.stripTrailingZeros()?.toPlainString()
    }

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
        val current: Map<Nutrient, List<NutrientValue>>
        val currentStable: Set<Nutrient>
        var currentCorrected = emptySet<Nutrient>()
        when (mode) {
            ScanMode.AUTO -> {
                current = auto.observe(lines, capturedAtMillis, isPhoto)
                currentStable = auto.stableNutrients
                currentCorrected = auto.correctedNutrients
                layoutWords = auto.words
                layoutFragmentCount = auto.fragmentCount
            }
            ScanMode.SPATIAL -> {
                layout.observe(lines, capturedAtMillis, isPhoto)
                current = layout.nutrients
                currentStable = layout.stableNutrients
                layoutWords = layout.words
                layoutFragmentCount = layout.fragmentCount
            }
            ScanMode.LINE -> {
                current = scan.observe(lines, capturedAtMillis, isPhoto)
                currentStable = scan.stableNutrients
            }
        }
        // Everything found stays until New scan: values are only replaced, and readiness only lost when the value changes.
        val previous = nutrients
        nutrients = (previous + current).toSortedMap()
        stableNutrients = currentStable.filterTo(mutableSetOf()) { it in current } +
            stableNutrients.filter { nutrients[it] == previous[it] }
        correctedNutrients = (currentCorrected.filterTo(mutableSetOf()) { it in current } +
            correctedNutrients.filter { it !in current }) - stableNutrients
        numericFallback = fallback.observe(lines, capturedAtMillis, nutrients, isPhoto)
    }

    fun setMode(mode: ScanMode, timestampMillis: Long) {
        if (this.mode == mode) return
        this.mode = mode
        resetOcr(timestampMillis)
    }

    private fun resetOcr(timestampMillis: Long) {
        resetAtMillis = timestampMillis
        scan.reset(timestampMillis)
        fallback.reset(timestampMillis)
        layout.reset(timestampMillis)
        auto.reset(timestampMillis)
        layoutWords = emptyList()
        layoutFragmentCount = 0
        recognizedText = ""
        nutrients = emptyMap()
        stableNutrients = emptySet()
        correctedNutrients = emptySet()
        numericFallback = NumericFallbackResult()
    }

    fun reset(timestampMillis: Long) {
        resetOcr(timestampMillis)
        manualNutrients = emptyMap()
        geminiNutrients = emptyMap()
    }
}