package ee.mty.nutidataocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NutritionFallbackTest {
    @Test
    fun keepsConfidentNumbersDespiteUnreadableLabelsButRejectsNoiseAndSingleFrameInference() {
        val fallback = NutritionFallback()
        val lines = listOf(OcrLine(
            text = "fuzzy 125 kcal 5 g 20 g 0 g 99 25% 123456789",
            confidence = 0.1f,
            tokens = listOf(
                OcrToken("fuzzy", 0.1f), OcrToken("125", 0.99f), OcrToken("kcal", 0.99f),
                OcrToken("5g", 0.95f), OcrToken("20g", 0.95f), OcrToken("0g", 0.95f),
                OcrToken("99", 0.4f), OcrToken("25%", 0.99f), OcrToken("123456789", 0.99f),
            ),
        ))
        val first = fallback.observe(lines, 0, emptyMap())
        assertEquals(setOf("125", "5", "20", "0"), first.numbers.map { it.number.amount }.toSet())
        assertTrue(first.hypotheses.isEmpty())
        assertTrue(fallback.observe(lines, 10, emptyMap()).hypotheses.isEmpty())
        val result = fallback.observe(lines, 300, emptyMap())
        assertTrue(result.hypotheses.isNotEmpty())
        val best = result.hypotheses.first()
        assertEquals(125.0, best.calculatedCalories, 0.001)
        assertEquals(5.0, best.fat, 0.001)
        assertEquals(setOf(0.0, 20.0), setOf(best.carbohydrates, best.protein))
        assertTrue(best.carbsProteinAmbiguous)
    }

    @Test
    fun labelledProteinDisambiguatesCarbsAndProteinWithoutChangingLabelledReadings() {
        val fallback = NutritionFallback()
        val labelled = mapOf(Nutrient.PROTEIN to listOf(NutrientValue("20", "g")))
        val lines = numbers("125kcal", "5g", "20g", "0g")
        fallback.observe(lines, 0, labelled)
        val best = fallback.observe(lines, 300, labelled).hypotheses.first()
        assertEquals(20.0, best.protein, 0.001)
        assertEquals(0.0, best.carbohydrates, 0.001)
        assertFalse(best.carbsProteinAmbiguous)
        assertEquals(listOf(NutrientValue("20", "g")), labelled[Nutrient.PROTEIN])
    }

    @Test
    fun doesNotReuseOneNumberForTwoNutrientsOrMixKnownColumns() {
        val fallback = NutritionFallback()
        val lines = numbers("80kcal", "0g", "10g")
        fallback.observe(lines, 0, emptyMap())
        assertTrue(fallback.observe(lines, 300, emptyMap()).hypotheses.isEmpty())
        val columns = mapOf(Nutrient.ENERGY_KCAL to listOf(NutrientValue("125", "kcal"), NutrientValue("250", "kcal")))
        val complete = numbers("125kcal", "5g", "20g", "0g")
        fallback.observe(complete, 600, columns)
        assertTrue(fallback.observe(complete, 900, columns).hypotheses.isEmpty())
    }

    @Test
    fun supportsKilojoulesAndKeepsNumbersUntilResetWithoutAcceptingOldFrames() {
        val fallback = NutritionFallback()
        val lines = numbers("523kJ", "5g", "20g", "0g")
        fallback.observe(lines, 0, emptyMap())
        assertEquals(125.0, fallback.observe(lines, 300, emptyMap()).hypotheses.first().calories, 0.001)
        assertEquals(4, fallback.observe(emptyList(), 60_000, emptyMap()).numbers.size)
        fallback.reset(61_000)
        assertTrue(fallback.observe(lines, 60_999, emptyMap()).numbers.isEmpty())
    }

    private fun numbers(vararg values: String) = values.map { value ->
        OcrLine(text = value, tokens = listOf(OcrToken(value, 0.99f)))
    }
}