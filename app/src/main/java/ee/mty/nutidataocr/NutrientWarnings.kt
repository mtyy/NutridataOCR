package ee.mty.nutidataocr

import java.math.BigDecimal

internal enum class NutrientWarning { OVER_100, HIGH_SALT, OVER_FAT, OVER_CARBOHYDRATES, MACROS_OVER_100 }

internal fun nutrientWarnings(values: Map<Nutrient, List<NutrientValue>>): Map<Nutrient, NutrientWarning> {
    val grams = values.mapNotNull { (nutrient, list) -> list.singleOrNull()?.grams()?.let { nutrient to it } }.toMap()
    val warnings = mutableMapOf<Nutrient, NutrientWarning>()
    val macros = listOf(Nutrient.FAT, Nutrient.CARBOHYDRATES, Nutrient.PROTEIN)
    if (macros.sumOf { grams[it] ?: BigDecimal.ZERO } > HUNDRED) {
        macros.filter { it in grams }.forEach { warnings[it] = NutrientWarning.MACROS_OVER_100 }
    }
    if (grams.greater(Nutrient.SUGARS, Nutrient.CARBOHYDRATES)) warnings[Nutrient.SUGARS] = NutrientWarning.OVER_CARBOHYDRATES
    if (grams.greater(Nutrient.SATURATES, Nutrient.FAT)) warnings[Nutrient.SATURATES] = NutrientWarning.OVER_FAT
    grams[Nutrient.SALT]?.takeIf { it > MAX_TYPICAL_SALT }?.let { warnings[Nutrient.SALT] = NutrientWarning.HIGH_SALT }
    grams.filterValues { it > HUNDRED }.keys.forEach { warnings[it] = NutrientWarning.OVER_100 }
    return warnings
}

private fun Map<Nutrient, BigDecimal>.greater(part: Nutrient, whole: Nutrient): Boolean {
    val partGrams = this[part] ?: return false
    val wholeGrams = this[whole] ?: return false
    return partGrams > wholeGrams
}

private fun NutrientValue.grams(): BigDecimal? {
    val number = amount.trimStart('<', '>').trim().replace(',', '.').toBigDecimalOrNull() ?: return null
    return when (unit) {
        "", "g" -> number
        "mg" -> number.movePointLeft(3)
        else -> null
    }
}

private val HUNDRED = BigDecimal(100)
private val MAX_TYPICAL_SALT = BigDecimal(6)
