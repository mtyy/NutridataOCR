package ee.mty.nutidataocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NutritionScanTest {
    @Test
    fun keepsNutrientsAcrossPartialAndUnreadableFramesAndResetsForNextProduct() {
        val scan = NutritionScan()
        scan.observe(listOf(OcrLine("Fat 8 g")), 0)
        scan.observe(listOf(OcrLine("Protein 12 g")), 100)
        scan.observe(listOf(OcrLine("Fat x g")), 600)
        scan.observe(emptyList(), 60_000)

        assertEquals(
            mapOf(
                Nutrient.FAT to listOf(NutrientValue("8", "g")),
                Nutrient.PROTEIN to listOf(NutrientValue("12", "g")),
            ),
            scan.nutrients,
        )
        scan.reset(61_000)
        scan.observe(listOf(OcrLine("Fat 8 g")), 60_999)
        assertTrue(scan.nutrients.isEmpty())
        scan.observe(listOf(OcrLine("Salt 1 g")), 61_100)
        assertEquals(mapOf(Nutrient.SALT to listOf(NutrientValue("1", "g"))), scan.nutrients)
    }

    @Test
    fun repeatedClearerReadingCorrectsOldValueButOneLargeOutlierDoesNot() {
        val scan = NutritionScan()
        repeat(20) { index ->
            scan.observe(listOf(OcrLine("126 kcal", confidence = 0.55f, textHeightPx = 12f)), index * 300L)
        }
        val clearerReading = listOf(OcrLine("125 kcal", confidence = 0.99f, textHeightPx = 36f))
        scan.observe(clearerReading, 6_000)
        assertEquals(listOf(NutrientValue("126", "kcal")), scan.nutrients[Nutrient.ENERGY_KCAL])
        scan.observe(clearerReading, 6_300)
        assertEquals(listOf(NutrientValue("125", "kcal")), scan.nutrients[Nutrient.ENERGY_KCAL])
        scan.observe(listOf(OcrLine("725 kcal", confidence = 1f, textHeightPx = 200f)), 6_600)
        assertEquals(listOf(NutrientValue("125", "kcal")), scan.nutrients[Nutrient.ENERGY_KCAL])
    }

    @Test
    fun majorityWinsDespiteFormattingChangesFlickerAndRapidDuplicateFrames() {
        val scan = NutritionScan()
        repeat(5) { index ->
            scan.observe(listOf(OcrLine("Protein 8.0 g")), index * 300L)
        }
        repeat(8) { index ->
            val timestamp = 1_500 + index * 600L
            scan.observe(listOf(OcrLine("Protein 6 g")), timestamp)
            repeat(10) { duplicate ->
                scan.observe(listOf(OcrLine("Protein 6 g")), timestamp + duplicate + 1)
            }
            assertEquals(listOf(NutrientValue("8", "g")), scan.nutrients[Nutrient.PROTEIN])
            scan.observe(listOf(OcrLine("Protein 8.00 g")), timestamp + 300)
        }
        assertEquals(listOf(NutrientValue("8", "g")), scan.nutrients[Nutrient.PROTEIN])
    }
}