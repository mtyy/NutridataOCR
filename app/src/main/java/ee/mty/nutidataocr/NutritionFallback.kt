package ee.mty.nutidataocr

import java.util.Locale
import kotlin.math.abs

internal data class NumberEvidence(
    val number: NutrientValue,
    val samples: Int,
    val confidence: Float,
    val occurrences: Int,
    val lastSeenMillis: Long,
)

internal data class MacroHypothesis(
    val calories: Double,
    val fat: Double,
    val carbohydrates: Double,
    val protein: Double,
    val calculatedCalories: Double,
    val carbsProteinAmbiguous: Boolean,
)

internal data class NumericFallbackResult(
    val numbers: List<NumberEvidence> = emptyList(),
    val hypotheses: List<MacroHypothesis> = emptyList(),
)

internal class NutritionFallback {
    private val observations = mutableMapOf<NutrientValue, NumberEvidence>()
    private var resetAtMillis = Long.MIN_VALUE
    private var latestResult = NumericFallbackResult()
    private var previousLabelled = emptyMap<Nutrient, List<NutrientValue>>()

    fun observe(
        lines: List<OcrLine>,
        timestampMillis: Long,
        labelled: Map<Nutrient, List<NutrientValue>>,
    ): NumericFallbackResult {
        if (timestampMillis <= resetAtMillis) return result(labelled)
        val frame = lines.flatMap { readNumbers(it.tokens) }.groupBy({ it.first }, { it.second })
        for ((number, confidences) in frame) {
            val previous = observations[number]
            if (previous != null && timestampMillis - previous.lastSeenMillis < 300L) continue
            observations[number] = NumberEvidence(
                number = number,
                samples = ((previous?.samples ?: 0) + 1).coerceAtMost(12),
                confidence = previous?.let { it.confidence * 0.75f + confidences.max() * 0.25f }
                    ?: confidences.max(),
                occurrences = maxOf(previous?.occurrences ?: 0, confidences.size),
                lastSeenMillis = timestampMillis,
            )
        }
        observations.values.sortedByDescending { it.lastSeenMillis }.drop(24).forEach {
            observations.remove(it.number)
        }
        return result(labelled)
    }

    fun reset(timestampMillis: Long) {
        observations.clear()
        resetAtMillis = timestampMillis
        latestResult = NumericFallbackResult()
        previousLabelled = emptyMap()
    }

    private fun result(labelled: Map<Nutrient, List<NutrientValue>>): NumericFallbackResult {
        val numbers = observations.values.sortedWith(
            compareByDescending<NumberEvidence> { it.samples }
                .thenByDescending { it.confidence }.thenByDescending { it.lastSeenMillis }
        )
        if (numbers != latestResult.numbers || labelled != previousLabelled) {
            latestResult = NumericFallbackResult(numbers, inferMacros(numbers.filter { it.samples >= 2 }, labelled))
            previousLabelled = labelled.toMap()
        }
        return latestResult
    }

    private fun readNumbers(tokens: List<OcrToken>): List<Pair<NutrientValue, Float>> =
        tokens.mapIndexedNotNull { index, token ->
            if (!token.confidence.isFinite() || token.confidence < MIN_CONFIDENCE) return@mapIndexedNotNull null
            val match = NUMBER.matchEntire(token.text.trim().lowercase(Locale.ROOT))
                ?: return@mapIndexedNotNull null
            val previous = tokens.getOrNull(index - 1)?.text?.trim()
            val next = tokens.getOrNull(index + 1)
            val nextText = next?.text?.trim()?.lowercase(Locale.ROOT)
            if (previous in listOf("<", ">", "-", "/") || nextText in listOf("%", "/", "kg", "ml", "l")) {
                return@mapIndexedNotNull null
            }
            var unit = match.groupValues[2]
            var confidence = token.confidence
            if (unit.isEmpty() && nextText in listOf("g", "mg", "kcal", "kj")) {
                if (next == null || !next.confidence.isFinite() || next.confidence < MIN_CONFIDENCE) {
                    return@mapIndexedNotNull null
                }
                unit = nextText.orEmpty()
                confidence = minOf(confidence, next.confidence)
            }
            val amount = match.groupValues[1].replace(',', '.').toBigDecimal().stripTrailingZeros().toPlainString()
            NutrientValue(amount, if (unit == "kj") "kJ" else unit) to confidence
        }

    private fun inferMacros(
        numbers: List<NumberEvidence>,
        labelled: Map<Nutrient, List<NutrientValue>>,
    ): List<MacroHypothesis> {
        val relevant = setOf(Nutrient.ENERGY_KCAL, Nutrient.ENERGY_KJ, Nutrient.FAT,
            Nutrient.CARBOHYDRATES, Nutrient.PROTEIN, Nutrient.FIBRE)
        if (labelled.any { (nutrient, values) ->
                nutrient in relevant && (values.size != 1 || values.single().amount.toDoubleOrNull() == null)
            }) return emptyList()

        fun known(nutrient: Nutrient): Double? = labelled[nutrient]?.singleOrNull()?.let { value ->
            val amount = value.amount.toDoubleOrNull() ?: return@let null
            when (value.unit) {
                "g", "kcal" -> amount
                "mg" -> amount / 1000.0
                "kJ" -> amount / 4.184
                else -> null
            }
        }

        val knownFat = known(Nutrient.FAT)
        val knownCarbs = known(Nutrient.CARBOHYDRATES)
        val knownProtein = known(Nutrient.PROTEIN)
        if (knownFat != null && knownCarbs != null && knownProtein != null) return emptyList()
        val knownCalories = known(Nutrient.ENERGY_KCAL) ?: known(Nutrient.ENERGY_KJ)
        val explicitEnergy = numbers.filter { it.number.unit in listOf("kcal", "kJ") }
            .map { it.number.amount.toDouble() / if (it.number.unit == "kJ") 4.184 else 1.0 }
        val energy = knownCalories?.let { listOf(it) } ?: explicitEnergy.ifEmpty {
            numbers.filter { it.number.unit.isEmpty() }.take(12).map { it.number.amount.toDouble() }
        }
        val grams = numbers.filter { it.number.unit in listOf("", "g") }
            .groupBy { it.number.amount.toDouble() }.entries.take(12)
            .associate { (amount, evidence) -> amount to evidence.maxOf { it.occurrences } }
        val fats = knownFat?.let { listOf(it) } ?: grams.keys.toList()
        val carbs = knownCarbs?.let { listOf(it) } ?: grams.keys.toList()
        val proteins = knownProtein?.let { listOf(it) } ?: grams.keys.toList()
        val knownGrams = listOf(Nutrient.FAT, Nutrient.CARBOHYDRATES, Nutrient.PROTEIN,
            Nutrient.FIBRE, Nutrient.SALT, Nutrient.SATURATES, Nutrient.SUGARS).mapNotNull { known(it) }
        val fibreCalories = 2.0 * (known(Nutrient.FIBRE) ?: 0.0)
        val hypotheses = mutableListOf<MacroHypothesis>()

        for (calories in energy.distinct().filter { it > 0.0 && it <= 2000.0 }.take(12)) {
            for (fat in fats) for (carbohydrates in carbs) for (protein in proteins) {
                if (knownCarbs == null && knownProtein == null && carbohydrates > protein) continue
                val calculated = 9.0 * fat + 4.0 * (carbohydrates + protein) + fibreCalories
                if (abs(calculated - calories) > maxOf(5.0, calories * 0.05)) continue
                val unassigned = listOfNotNull(
                    fat.takeIf { knownFat == null }, carbohydrates.takeIf { knownCarbs == null },
                    protein.takeIf { knownProtein == null },
                )
                if (unassigned.groupingBy { it }.eachCount().any { (amount, count) ->
                        count + knownGrams.count { it == amount } > grams.getOrDefault(amount, 0)
                    }) continue
                hypotheses += MacroHypothesis(
                    calories, fat, carbohydrates, protein, calculated,
                    carbsProteinAmbiguous = knownCarbs == null && knownProtein == null && carbohydrates != protein,
                )
            }
        }
        return hypotheses.distinct().sortedBy { abs(it.calculatedCalories - it.calories) / it.calories }.take(3)
    }

    private companion object {
        const val MIN_CONFIDENCE = 0.85f
        val NUMBER = Regex("""^(\d{1,4}(?:[.,]\d{1,3})?)\s*(kcal|kj|mg|g)?$""")
    }
}