package ee.mty.nutidataocr

import kotlin.math.pow

internal data class OcrToken(val text: String, val confidence: Float, val box: OcrBox? = null)

internal data class OcrBox(
    val centerX: Double,
    val centerY: Double,
    val width: Double,
    val height: Double,
    val angle: Double = 0.0,
) {
    val valid: Boolean
        get() = listOf(centerX, centerY, width, height, angle).all { it.isFinite() } && width > 0 && height > 0
}

internal data class OcrLine(
    val text: String,
    val confidence: Float = 0.5f,
    val textHeightPx: Float = 24f,
    val tokens: List<OcrToken> = emptyList(),
)

internal val REQUIRED_SCAN_NUTRIENTS = listOf(
    Nutrient.FAT,
    Nutrient.SATURATES,
    Nutrient.CARBOHYDRATES,
    Nutrient.SUGARS,
    Nutrient.PROTEIN,
    Nutrient.SALT,
)

internal class NutritionScan {
    private data class Sample(
        val values: List<NutrientValue>,
        val confidence: Float,
        val weight: Double,
        val timestampMillis: Long,
    )

    private val history = mutableMapOf<Nutrient, ArrayDeque<Sample>>()
    private val selected = mutableMapOf<Nutrient, List<NutrientValue>>()
    private var resetAtMillis = Long.MIN_VALUE

    val nutrients: Map<Nutrient, List<NutrientValue>>
        get() = selected.toSortedMap()

    val stableNutrients: Set<Nutrient>
        get() = selected.keys.filterTo(mutableSetOf()) { nutrient ->
            val values = selected.getValue(nutrient)
            val samples = history.getValue(nutrient)
            val clearMatches = samples.count {
                it.values == values && it.confidence >= MIN_STABLE_CONFIDENCE
            }
            var matchingWeight = 0.0
            var totalWeight = 0.0
            samples.reversed().forEachIndexed { age, observation ->
                val weight = observation.weight * RECENCY_WEIGHT.pow(age)
                totalWeight += weight
                if (observation.values == values) matchingWeight += weight
            }
            values.size == 1 && clearMatches >= MIN_STABLE_SAMPLES &&
                matchingWeight >= totalWeight * MIN_STABLE_AGREEMENT
        }

    fun observe(
        lines: List<OcrLine>,
        timestampMillis: Long,
        isPhoto: Boolean = false,
    ): Map<Nutrient, List<NutrientValue>> {
        if (timestampMillis <= resetAtMillis) return nutrients
        val frameSamples = mutableMapOf<Nutrient, Sample>()
        for (line in lines) {
            val confidence = line.confidence.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.5f
            val sizeWeight = line.textHeightPx.takeIf { it.isFinite() && it > 0f }
                ?.let { (it / 24f).coerceIn(0.5f, 1.5f) } ?: 1f
            val weight = (0.25 + 0.75 * confidence) * sizeWeight
            for ((nutrient, values) in parseNutrition(line.text)) {
                val sample = Sample(values.map { it.normalized() }, confidence, weight, timestampMillis)
                if (weight > (frameSamples[nutrient]?.weight ?: 0.0)) {
                    frameSamples[nutrient] = sample
                }
            }
        }

        for ((nutrient, sample) in frameSamples) {
            val samples = history.getOrPut(nutrient) { ArrayDeque() }
            val previousTimestamp = samples.lastOrNull()?.timestampMillis
            if (previousTimestamp != null && (
                timestampMillis <= previousTimestamp ||
                    (!isPhoto && timestampMillis - previousTimestamp < SAMPLE_INTERVAL_MILLIS)
                )) {
                continue
            }
            samples.addLast(sample)
            if (samples.size > HISTORY_SIZE) samples.removeFirst()

            val scores = mutableMapOf<List<NutrientValue>, Double>()
            samples.reversed().forEachIndexed { age, observation ->
                val score = observation.weight * RECENCY_WEIGHT.pow(age)
                scores[observation.values] = scores.getOrDefault(observation.values, 0.0) + score
            }
            val best = scores.maxBy { it.value }
            val current = selected[nutrient]
            val supportingSamples = samples.count { it.values == best.key }
            if (current == null || (
                supportingSamples >= MIN_CHALLENGER_SAMPLES &&
                    best.value > scores.getOrDefault(current, 0.0) * SWITCH_MARGIN
                )) {
                selected[nutrient] = best.key
            }
        }
        return nutrients
    }

    fun reset(timestampMillis: Long) {
        history.clear()
        selected.clear()
        resetAtMillis = timestampMillis
    }

    private fun NutrientValue.normalized(): NutrientValue {
        val comparison = amount.takeWhile { it == '<' || it == '>' }
        val number = amount.removePrefix(comparison).toBigDecimal().stripTrailingZeros().toPlainString()
        return copy(amount = comparison + number)
    }

    private companion object {
        const val SAMPLE_INTERVAL_MILLIS = 300L
        const val HISTORY_SIZE = 12
        const val RECENCY_WEIGHT = 0.85
        const val SWITCH_MARGIN = 1.25
        const val MIN_CHALLENGER_SAMPLES = 2
        const val MIN_STABLE_CONFIDENCE = 0.85f
        const val MIN_STABLE_SAMPLES = 3
        const val MIN_STABLE_AGREEMENT = 0.75
    }
}